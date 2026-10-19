package com.drzewiecki.shitcloud.mongo;

/**
 * Platform collections. The {@code _} prefix keeps them apart from app data, which lives in {@code docs}.
 */
public final class MongoCollections {

    public static final String APPS = "_apps";
    public static final String APP_VERSIONS = "_app_versions";
    /** App data: every app's documents, partitioned by space (the app's slug) and collection. */
    public static final String DOCS = "docs";
    /** Earlier states of app documents, written in the same transaction as the change. */
    public static final String HISTORY = "_history";

    private MongoCollections() {
    }
}
