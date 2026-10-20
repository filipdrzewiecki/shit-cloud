package com.drzewiecki.shitcloud.chat;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The parts of the AI SDK UI message stream v1, one factory per part type, fields in the order of the spec
 * ({@code .claude/docs/chat-protocol.md}). A wrong field name is not an error on the client, just an empty
 * message, so the names live only here.
 */
public final class UiParts {

    private UiParts() {
    }

    public static Map<String, Object> start(String messageId) {
        return part("start", "messageId", messageId);
    }

    public static Map<String, Object> startStep() {
        return part("start-step");
    }

    public static Map<String, Object> finishStep() {
        return part("finish-step");
    }

    public static Map<String, Object> textStart(String id) {
        return part("text-start", "id", id);
    }

    public static Map<String, Object> textDelta(String id, String delta) {
        return part("text-delta", "id", id, "delta", delta);
    }

    public static Map<String, Object> textEnd(String id) {
        return part("text-end", "id", id);
    }

    public static Map<String, Object> toolInputAvailable(String toolCallId, String toolName, Object input) {
        return part("tool-input-available", "toolCallId", toolCallId, "toolName", toolName, "input", input);
    }

    public static Map<String, Object> toolOutputAvailable(String toolCallId, Object output) {
        return part("tool-output-available", "toolCallId", toolCallId, "output", output);
    }

    public static Map<String, Object> toolOutputError(String toolCallId, String errorText) {
        return part("tool-output-error", "toolCallId", toolCallId, "errorText", errorText);
    }

    /**
     * {@code data-<name>}. A later part with the same {@code id} replaces the earlier one on the client; a
     * transient part is delivered but never becomes part of the message.
     */
    public static Map<String, Object> data(String name, String id, Object data, boolean isTransient) {
        Map<String, Object> part = part("data-" + name);
        if (id != null) {
            part.put("id", id);
        }
        part.put("data", data);
        if (isTransient) {
            part.put("transient", true);
        }
        return part;
    }

    public static Map<String, Object> finish() {
        return part("finish");
    }

    public static Map<String, Object> error(String errorText) {
        return part("error", "errorText", errorText);
    }

    private static Map<String, Object> part(String type, Object... fields) {
        Map<String, Object> part = new LinkedHashMap<>();
        part.put("type", type);
        for (int i = 0; i < fields.length; i += 2) {
            part.put((String) fields[i], fields[i + 1]);
        }
        return part;
    }
}
