package com.drzewiecki.shitcloud.migration;

/**
 * Documents a migration run went through: {@code documents = changed + unchanged + removed + failed}.
 */
public record MigrationCounts(long documents, long changed, long unchanged, long removed, long failed) {

    public static final MigrationCounts NONE = new MigrationCounts(0, 0, 0, 0, 0);

    MigrationCounts plus(MigrationCounts other) {
        return new MigrationCounts(documents + other.documents, changed + other.changed,
                unchanged + other.unchanged, removed + other.removed, failed + other.failed);
    }
}
