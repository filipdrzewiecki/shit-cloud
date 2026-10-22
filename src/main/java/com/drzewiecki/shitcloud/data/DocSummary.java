package com.drzewiecki.shitcloud.data;

import java.time.Instant;

/**
 * One line of a collection's document list.
 *
 * @param removed   the document is gone; its last state is in history
 * @param version   the current version, or the removed document's last one
 * @param updatedBy who wrote the current state, or who removed the document
 * @param updatedAt when, likewise
 * @param preview   the start of the document's JSON
 */
public record DocSummary(String id, boolean removed, long version, String updatedBy, Instant updatedAt,
                         String preview) {
}
