package com.drzewiecki.shitcloud.model;

/**
 * What deleting an app with its data removed.
 */
public record DeleteResult(long documents, long versions, long history) {
}
