package com.drzewiecki.shitcloud.migration;

import java.util.List;
import java.util.Map;

/**
 * What a check run of a proposed migration found; nothing was written. The builder approves on this.
 *
 * @param byCollection the counts of each migrated collection
 * @param fields       per collection, how the documents' top-level fields change (only fields that do)
 * @param examples     a few converted or removed documents, before and after
 * @param failures     the first documents the transform failed on
 */
public record MigrationCheck(
        long id,
        String space,
        List<String> collections,
        String summary,
        MigrationCounts counts,
        Map<String, MigrationCounts> byCollection,
        Map<String, Map<String, FieldChange>> fields,
        List<Example> examples,
        List<Failure> failures) {

    /** How many documents gain, lose or change a field. */
    public record FieldChange(long added, long removed, long changed) {
    }

    /** @param after null when the document is removed */
    public record Example(String collection, String id, Map<String, Object> before, Map<String, Object> after) {
    }

    public record Failure(String collection, String id, String error) {
    }
}
