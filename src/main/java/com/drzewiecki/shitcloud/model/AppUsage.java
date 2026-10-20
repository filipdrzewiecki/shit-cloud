package com.drzewiecki.shitcloud.model;

/**
 * How much an app holds: shown before deleting it with its data.
 */
public record AppUsage(long documents, long collections, long versions) {
}
