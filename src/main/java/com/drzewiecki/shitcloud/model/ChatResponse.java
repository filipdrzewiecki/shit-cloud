package com.drzewiecki.shitcloud.model;

/**
 * {@code draftHtml} is null when the reply does not change the preview.
 */
public record ChatResponse(ChatMessage reply, String draftHtml) {
}
