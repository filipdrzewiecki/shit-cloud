package com.drzewiecki.shitcloud.mongo;

/**
 * Platform collections. The {@code _} prefix keeps them apart from app data, which lives in {@code docs}.
 */
public final class MongoCollections {

    public static final String APPS = "_apps";
    public static final String APP_VERSIONS = "_app_versions";

    private MongoCollections() {
    }
}
