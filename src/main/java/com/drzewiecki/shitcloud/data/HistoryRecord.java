package com.drzewiecki.shitcloud.data;

import com.drzewiecki.shitcloud.mongo.MongoCollections;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Map;

/**
 * A state a document had before it was changed or removed. {@code version}, {@code data}, {@code updatedBy}
 * and {@code updatedAt} describe that earlier state; {@code changedBy}/{@code changedAt} the change that
 * replaced it. Expires after {@code data.history-ttl}.
 *
 * @param op {@code update} or {@code delete}
 */
@Document(MongoCollections.HISTORY)
public record HistoryRecord(
        @Id String id,
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
