package com.drzewiecki.shitcloud.data;

import java.time.Instant;
import java.util.Map;

/**
 * One document of an app ({@code docs}). The app's own fields are nested under {@code data}, so they can never collide with
 * or reach the platform's fields.
 *
 * @param space   the app's slug: whose data this is
 * @param docId   the id the app knows the document by, unique within space and collection
 * @param version 1 on insert, +1 on every change; the previous state is then in {@link HistoryRecord}
 *                ({@code doc_history})
 */
public record DocRecord(
        Long id,
        String space,
        String collection,
        String docId,
        Map<String, Object> data,
        long version,
        String createdBy,
        Instant createdAt,
        String updatedBy,
        Instant updatedAt) {
}
