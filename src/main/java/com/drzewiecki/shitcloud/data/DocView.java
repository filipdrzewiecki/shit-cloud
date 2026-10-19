package com.drzewiecki.shitcloud.data;

import java.time.Instant;
import java.util.Map;

/**
 * A document as the Data API returns it; {@code id} is the app's id, not Mongo's.
 */
public record DocView(
        String id,
        Map<String, Object> data,
        long version,
        String createdBy,
        Instant createdAt,
        String updatedBy,
        Instant updatedAt) {

    static DocView of(DocRecord doc) {
        return new DocView(doc.docId(), doc.data(), doc.version(), doc.createdBy(), doc.createdAt(),
                doc.updatedBy(), doc.updatedAt());
    }
}
