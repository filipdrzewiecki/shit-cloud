package com.drzewiecki.shitcloud.model;

import java.time.Instant;

/**
 * One app in the registry ({@code apps}) with its current HTML. Earlier versions are in {@link AppVersion}.
 *
 * @param owner      who created the app; a redeploy by someone else does not change it
 * @param updatedBy  who deployed the current version
 * @param archivedAt when the app was archived (hidden from the list, data kept); null while it is live
 */
public record AppRecord(
        String slug,
        String name,
        String description,
        String html,
        String owner,
        int version,
        Instant lastUsedAt,
        boolean archived,
        Instant createdAt,
        Instant updatedAt,
        String updatedBy,
        Instant archivedAt,
        String archivedBy) {

    public AppSummary summary() {
        return new AppSummary(slug, name, description, owner, lastUsedAt, archived, version, archivedAt, archivedBy);
    }
}
