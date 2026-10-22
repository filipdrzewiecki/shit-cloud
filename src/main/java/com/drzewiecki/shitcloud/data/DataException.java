package com.drzewiecki.shitcloud.data;

import org.springframework.http.HttpStatus;

/**
 * A Data API failure an app can act on: the code is stable (apps branch on it), the message readable.
 */
public class DataException extends RuntimeException {

    public static final String BAD_REQUEST = "BAD_REQUEST";
    public static final String NO_SUCH_APP = "NO_SUCH_APP";
    public static final String VERSION_CONFLICT = "VERSION_CONFLICT";
    public static final String CONFLICT = "CONFLICT";
    public static final String TOO_LARGE = "TOO_LARGE";
    /** A data migration failed on the documents as they are now; nothing was changed. */
    public static final String MIGRATION_FAILED = "MIGRATION_FAILED";
    /** The migration is not (or no longer) the one that can be applied or undone. */
    public static final String MIGRATION_STALE = "MIGRATION_STALE";
    public static final String NO_SUCH_MIGRATION = "NO_SUCH_MIGRATION";
    /** No such earlier state of the document in history (expired after 90 days, or never existed). */
    public static final String NO_SUCH_VERSION = "NO_SUCH_VERSION";

    private final HttpStatus status;
    private final String code;

    public DataException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    static DataException badRequest(String message) {
        return new DataException(HttpStatus.BAD_REQUEST, BAD_REQUEST, message);
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
