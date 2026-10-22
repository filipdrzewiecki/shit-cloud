package com.drzewiecki.shitcloud.model;

/**
 * @param historyId       the earlier state to put back ({@code HistoryView.historyId})
 * @param expectedVersion the document's version when the builder looked; 0 when it was removed
 */
public record RestoreRequest(Long historyId, Long expectedVersion) {
}
