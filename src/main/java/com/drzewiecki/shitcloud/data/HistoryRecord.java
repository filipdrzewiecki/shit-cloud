package com.drzewiecki.shitcloud.data;

import java.time.Instant;
import java.util.Map;

/**
 * A state a document had before it was changed or removed ({@code doc_history}). {@code version}, {@code data}, {@code updatedBy}
 * and {@code updatedAt} describe that earlier state; {@code changedBy}/{@code changedAt} the change that
 * replaced it. Removed by {@code ExpiryJob} after {@code data.history-ttl}.
 *
 * @param op {@code update} or {@code delete}
 */
public record HistoryRecord(
        Long id,
        String space,
        String collection,
        String docId,
        long version,
        String op,
        Map<String, Object> data,
        String updatedBy,
        Instant updatedAt,
        String changedBy,
        Instant changedAt) {

    static HistoryRecord of(DocRecord previous, String op, String changedBy, Instant changedAt) {
        return new HistoryRecord(null, previous.space(), previous.collection(), previous.docId(),
                previous.version(), op, previous.data(), previous.updatedBy(), previous.updatedAt(),
                changedBy, changedAt);
    }
}
