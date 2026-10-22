package com.drzewiecki.shitcloud.data;

import java.time.Instant;
import java.util.Map;

/**
 * An earlier state of a document, as the Data & history page shows it.
 *
 * @param historyId identifies the state: versions can repeat for one id when a removed document is created again
 * @param op        what replaced it: {@code update}, {@code delete}, {@code migrate}, {@code undo} or {@code restore}
 * @param updatedBy who wrote this state, and {@code updatedAt} when
 * @param changedBy who replaced it, and {@code changedAt} when
 */
public record HistoryView(long historyId, long version, String op, Map<String, Object> data, String updatedBy,
                          Instant updatedAt, String changedBy, Instant changedAt) {
}
