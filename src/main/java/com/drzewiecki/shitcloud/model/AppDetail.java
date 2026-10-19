package com.drzewiecki.shitcloud.model;

/**
 * @param html     the current HTML, for tools that rework it; the platform never renders it itself
 * @param frameUrl where the app runs, on the apps origin
 */
public record AppDetail(AppSummary app, String html, String frameUrl) {
}
