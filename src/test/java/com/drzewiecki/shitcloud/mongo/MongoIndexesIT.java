package com.drzewiecki.shitcloud.mongo;

import com.drzewiecki.shitcloud.IntegrationTest;
import com.drzewiecki.shitcloud.service.AppsProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MongoIndexesIT extends IntegrationTest {

    @Autowired
    private AppsProperties properties;

    @Test
    void startup_createsTheIndexes() {
        assertThat(indexNames(MongoCollections.APPS)).contains(MongoIndexes.LAST_USED_INDEX);

        List<IndexInfo> versionIndexes = mongo.indexOps(MongoCollections.APP_VERSIONS).getIndexInfo();
        assertThat(versionIndexes).anySatisfy(index -> {
            assertThat(index.getName()).isEqualTo(MongoIndexes.APP_VERSION_INDEX);
            assertThat(index.isUnique()).isTrue();
        });
        assertThat(ttl()).isEqualTo(properties.versionTtl());
    }

    @Test
    void changedTtl_isAppliedToTheExistingIndex() {
        AppsProperties shorter = new AppsProperties(Duration.ofDays(30), DataSize.ofMegabytes(5), false);
        try {
            new MongoIndexes(mongo, shorter).ensure();

            assertThat(ttl()).isEqualTo(Duration.ofDays(30));
        } finally {
            new MongoIndexes(mongo, properties).ensure();
        }
        assertThat(ttl()).isEqualTo(properties.versionTtl());
    }

    private Duration ttl() {
        return mongo.indexOps(MongoCollections.APP_VERSIONS).getIndexInfo().stream()
                .filter(index -> index.getName().equals(MongoIndexes.VERSION_TTL_INDEX))
                .findFirst()
                .flatMap(IndexInfo::getExpireAfter)
                .orElse(null);
    }

    private List<String> indexNames(String collection) {
        return mongo.indexOps(collection).getIndexInfo().stream().map(IndexInfo::getName).toList();
    }
}
