package com.drzewiecki.shitcloud.chat;

import java.time.Instant;

/**
 * {@code targetSlug} is set when the conversation works on an existing app, null while it creates a new one.
 */
public record Conversation(String id, String owner, String title, String targetSlug, Instant createdAt,
                           Instant updatedAt) {
}
