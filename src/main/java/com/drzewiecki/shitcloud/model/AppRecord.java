package com.drzewiecki.shitcloud.model;

import com.drzewiecki.shitcloud.mongo.MongoCollections;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * One app in the registry with its current HTML. Earlier versions are in {@link AppVersion}.
 *
 * @param owner     who created the app; a redeploy by someone else does not change it
 * @param updatedBy who deployed the current version
 */
@Document(MongoCollections.APPS)
public record AppRecord(
        @Id String slug,
        String name,
        String description,
        String html,
        String owner,
        int version,
        Instant lastUsedAt,
        boolean archived,
        Instant createdAt,
        Instant updatedAt,
        String updatedBy) {

    public AppSummary summary() {
        return new AppSummary(slug, name, description, owner, lastUsedAt, archived, version);
    }
}
