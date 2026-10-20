package com.drzewiecki.shitcloud.model;

import java.time.Instant;

/**
 * One deploy of an app ({@code app_versions}), kept for {@code apps.version-ttl}.
 *
 * @param appSlug the app's slug
 * @param author  who deployed this version
 */
public record AppVersion(
        Long id,
        String appSlug,
        int version,
        String name,
        String description,
        String html,
        String author,
        Instant createdAt) {
}
