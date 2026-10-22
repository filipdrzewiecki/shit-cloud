package com.drzewiecki.shitcloud.data;

import java.util.List;

/**
 * @param hasMore another page follows
 */
public record DocPage(List<DocSummary> items, boolean hasMore) {
}
