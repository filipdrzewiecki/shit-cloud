package com.drzewiecki.shitcloud.mongo;

import com.drzewiecki.shitcloud.service.AppsProperties;
import jakarta.annotation.PostConstruct;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Creates the platform collections and their indexes before the application takes traffic. Indexes
 * cannot be created inside a transaction, so they must exist before the first deploy.
 */
@Component
public class MongoIndexes {

    private static final Logger log = LoggerFactory.getLogger(MongoIndexes.class);

    static final String VERSION_TTL_INDEX = "createdAt_ttl";
    static final String APP_VERSION_INDEX = "appId_version_unique";
    static final String LAST_USED_INDEX = "lastUsedAt_desc";

    private final MongoTemplate mongo;
    private final AppsProperties properties;

    public MongoIndexes(MongoTemplate mongo, AppsProperties properties) {
        this.mongo = mongo;
        this.properties = properties;
    }

    @PostConstruct
    void ensure() {
        createCollection(MongoCollections.APPS);
        createCollection(MongoCollections.APP_VERSIONS);

        mongo.indexOps(MongoCollections.APPS).ensureIndex(
                new Index().on("lastUsedAt", Sort.Direction.DESC).named(LAST_USED_INDEX));
        mongo.indexOps(MongoCollections.APP_VERSIONS).ensureIndex(
                new Index().on("appId", Sort.Direction.ASC).on("version", Sort.Direction.ASC)
                        .unique().named(APP_VERSION_INDEX));
        ensureVersionTtl(properties.versionTtl());
    }

    private void createCollection(String name) {
        if (!mongo.collectionExists(name)) {
            mongo.createCollection(name);
        }
    }

    /**
     * Creating a TTL index with a different expiry than the existing one fails, so a changed property is
     * applied to the existing index with {@code collMod} instead.
     */
    private void ensureVersionTtl(Duration ttl) {
        long seconds = ttl.toSeconds();
        Optional<IndexInfo> existing = mongo.indexOps(MongoCollections.APP_VERSIONS).getIndexInfo().stream()
                .filter(index -> index.getName().equals(VERSION_TTL_INDEX))
                .findFirst();
        if (existing.isEmpty()) {
            mongo.indexOps(MongoCollections.APP_VERSIONS).ensureIndex(
                    new Index().on("createdAt", Sort.Direction.ASC).expire(ttl).named(VERSION_TTL_INDEX));
            return;
        }
        Duration current = existing.get().getExpireAfter().orElse(null);
        if (current == null || current.toSeconds() != seconds) {
            log.info("Changing the {} TTL from {} to {}", MongoCollections.APP_VERSIONS, current, ttl);
            Document index = new Document("name", VERSION_TTL_INDEX).append("expireAfterSeconds", seconds);
            mongo.executeCommand(new Document("collMod", MongoCollections.APP_VERSIONS).append("index", index));
        }
    }
}
