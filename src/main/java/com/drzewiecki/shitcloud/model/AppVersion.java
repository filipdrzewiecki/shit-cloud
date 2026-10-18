package com.drzewiecki.shitcloud.model;

import com.drzewiecki.shitcloud.mongo.MongoCollections;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * One deploy of an app, kept for the configured TTL ({@code apps.version-ttl}).
 *
 * @param appId  the app's slug
 * @param author who deployed this version
 */
@Document(MongoCollections.APP_VERSIONS)
public record AppVersion(
        @Id String id,
        String appId,
        int version,
        String name,
        String description,
        String html,
        String author,
        Instant createdAt) {
}
