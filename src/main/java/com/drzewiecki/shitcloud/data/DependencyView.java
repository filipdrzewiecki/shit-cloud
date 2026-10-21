package com.drzewiecki.shitcloud.data;

import java.time.Instant;

/**
 * One app's use of one collection of a space.
 *
 * @param app  the slug of the app that used the data (the space's own app too)
 * @param mode {@code read} or {@code write}
 */
public record DependencyView(String app, String appName, boolean archived, String collection, String mode,
                             Instant firstSeenAt, Instant lastSeenAt) {
}
