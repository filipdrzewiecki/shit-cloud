package com.drzewiecki.shitcloud.migration;

/**
 * @param restored    documents put back as they were before the data change
 * @param removed     documents that did not exist before it (written since) and were removed
 * @param htmlVersion the app's version after the undo; its HTML is the one from before the change
 */
public record MigrationUndo(long restored, long removed, Integer htmlVersion) {
}
