package com.drzewiecki.shitcloud.migration;

/**
 * A migration that cannot run or be applied at all, with a reason the model and the builder can read.
 */
public class MigrationException extends RuntimeException {

    public MigrationException(String message) {
        super(message);
    }
}
