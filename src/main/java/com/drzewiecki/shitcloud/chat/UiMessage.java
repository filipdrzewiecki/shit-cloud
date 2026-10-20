package com.drzewiecki.shitcloud.chat;

import java.util.List;
import java.util.Map;

/**
 * A chat message in the AI SDK UI shape ({@code UIMessage}): what the client renders and what a saved
 * conversation returns, so the client can pass it to {@code useChat} unchanged. Parts are kept as plain maps
 * because their fields depend on the type ({@code text}, {@code step-start}, {@code tool-<name>}, {@code data-<name>}).
 */
public record UiMessage(String id, String role, List<Map<String, Object>> parts) {

    public static final String USER = "user";
    public static final String ASSISTANT = "assistant";

    public static UiMessage userText(String id, String text) {
        return new UiMessage(id, USER, List.of(Map.of("type", "text", "text", text)));
    }

    /**
     * The text parts joined; empty when there are none.
     */
    public String text() {
        StringBuilder text = new StringBuilder();
        for (Map<String, Object> part : parts == null ? List.<Map<String, Object>>of() : parts) {
            if ("text".equals(part.get("type")) && part.get("text") instanceof String value) {
                text.append(value);
            }
        }
        return text.toString();
    }
}
