package com.drzewiecki.shitcloud.data;

import java.util.Map;

/**
 * An app's space as the build chat lists it.
 *
 * @param collections collection → number of documents
 */
public record SpaceSummary(String space, String name, String description, Map<String, Long> collections) {
}
