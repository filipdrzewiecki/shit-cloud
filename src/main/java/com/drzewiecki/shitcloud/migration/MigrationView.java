package com.drzewiecki.shitcloud.migration;

import java.time.Instant;
import java.util.List;

/**
 * A data change that was applied to an app (and maybe undone), for the app's page.
 *
 * @param checked   counts of the check the builder approved
 * @param result    counts of the run that applied it
 * @param copyUntil the documents as they were before are kept, and undo is possible, until then; null once gone
 * @param undoable  the app's latest applied data change, with its copy still there
 */
public record MigrationView(
        long id,
        String space,
        String status,
        List<String> collections,
        String summary,
        MigrationCounts checked,
        MigrationCounts result,
        Integer htmlVersionBefore,
        Integer htmlVersionAfter,
        String appliedBy,
        Instant appliedAt,
        String undoneBy,
        Instant undoneAt,
        Instant copyUntil,
        boolean undoable) {
}
