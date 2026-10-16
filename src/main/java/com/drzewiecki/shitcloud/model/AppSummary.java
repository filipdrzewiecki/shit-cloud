package com.drzewiecki.shitcloud.model;

import java.time.Instant;

public record AppSummary(
        String slug,
        String name,
        String description,
        String owner,
        Instant lastUsedAt,
        boolean archived,
        int version) {
}
