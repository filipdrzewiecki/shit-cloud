package com.drzewiecki.shitcloud.model;

/**
 * @param migrationId a checked data change of the app (from the build chat) to apply together with the HTML; null
 *                    to leave the data alone
 */
public record ReplaceHtmlRequest(String html, Long migrationId) {
}
