package com.drzewiecki.shitcloud.model;

import java.time.Instant;

/**
 * @param archivedAt when a builder archived the app; null while it is live
 * @param archivedBy who archived it
 */
public record AppSummary(
        String slug,
        String name,
        String description,
        String owner,
        Instant lastUsedAt,
        boolean archived,
        int version,
        Instant archivedAt,
        String archivedBy) {
}
