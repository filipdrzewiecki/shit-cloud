package com.drzewiecki.shitcloud.data;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * An app's data as one downloadable file: every collection's documents, in the shape the Data API returns them.
 */
public record ExportView(App app, Instant exportedAt, Map<String, List<DocView>> collections) {

    public record App(String slug, String name, int version) {
    }
}
