package com.drzewiecki.shitcloud.mongo;

import com.drzewiecki.shitcloud.data.DataProperties;
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
    static final String DOC_INDEX = "space_collection_docId_unique";
    static final String HISTORY_INDEX = "space_collection_docId_version";
    static final String HISTORY_TTL_INDEX = "changedAt_ttl";

    private final MongoTemplate mongo;
    private final AppsProperties properties;
    private final DataProperties dataProperties;

    public MongoIndexes(MongoTemplate mongo, AppsProperties properties, DataProperties dataProperties) {
        this.mongo = mongo;
        this.properties = properties;
        this.dataProperties = dataProperties;
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
        ensureTtl(MongoCollections.APP_VERSIONS, "createdAt", VERSION_TTL_INDEX, properties.versionTtl());

        createCollection(MongoCollections.DOCS);
        createCollection(MongoCollections.HISTORY);
        // Also the index every query uses: space and collection are always the first conditions.
        mongo.indexOps(MongoCollections.DOCS).ensureIndex(
                new Index().on("space", Sort.Direction.ASC).on("collection", Sort.Direction.ASC)
                        .on("docId", Sort.Direction.ASC).unique().named(DOC_INDEX));
        mongo.indexOps(MongoCollections.HISTORY).ensureIndex(
                new Index().on("space", Sort.Direction.ASC).on("collection", Sort.Direction.ASC)
                        .on("docId", Sort.Direction.ASC).on("version", Sort.Direction.ASC).named(HISTORY_INDEX));
        ensureTtl(MongoCollections.HISTORY, "changedAt", HISTORY_TTL_INDEX, dataProperties.historyTtl());
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
    private void ensureTtl(String collection, String field, String indexName, Duration ttl) {
        long seconds = ttl.toSeconds();
        Optional<IndexInfo> existing = mongo.indexOps(collection).getIndexInfo().stream()
                .filter(index -> index.getName().equals(indexName))
                .findFirst();
        if (existing.isEmpty()) {
            mongo.indexOps(collection).ensureIndex(
                    new Index().on(field, Sort.Direction.ASC).expire(ttl).named(indexName));
            return;
        }
        Duration current = existing.get().getExpireAfter().orElse(null);
        if (current == null || current.toSeconds() != seconds) {
            log.info("Changing the {} TTL from {} to {}", collection, current, ttl);
            Document index = new Document("name", indexName).append("expireAfterSeconds", seconds);
            mongo.executeCommand(new Document("collMod", collection).append("index", index));
        }
    }
}
