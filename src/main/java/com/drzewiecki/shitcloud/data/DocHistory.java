package com.drzewiecki.shitcloud.data;

import java.util.List;

/**
 * @param current   the document now; null when it is removed
 * @param versions  its earlier states, newest first
 * @param truncated older states exist than the ones listed
 */
public record DocHistory(DocView current, List<HistoryView> versions, boolean truncated) {
}
