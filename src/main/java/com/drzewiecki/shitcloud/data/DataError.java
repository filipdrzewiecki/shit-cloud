package com.drzewiecki.shitcloud.data;

/**
 * Error body of the Data API, passed on to the app by the host page.
 */
public record DataError(String code, String message) {
}
