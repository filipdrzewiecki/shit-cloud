package com.drzewiecki.shitcloud.chat;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Saved messages (client shape) → the messages resent to the model.
 * <ul>
 *     <li>An answer becomes one assistant message per step: its text and tool calls, then the tool results.</li>
 *     <li>A data part goes to the model as a line of text next to the tool result that produced it. Only the
 *     latest part per name and id is sent in full; earlier ones are replaced by a short note, so only the
 *     current draft's HTML costs tokens.</li>
 *     <li>A tool call without a result (the answer was stopped) is left out: the model API wants every call
 *     answered.</li>
 * </ul>
 */
public class HistoryMapper {

    private static final String TOOL_PREFIX = "tool-";
    private static final String DATA_PREFIX = "data-";

    private final JsonMapper json;

    public HistoryMapper(JsonMapper json) {
        this.json = json;
    }

    public List<Message> toPrompt(List<UiMessage> history) {
        Map<String, Map<String, Object>> latestData = latestDataParts(history);
        List<Message> messages = new ArrayList<>();
        for (UiMessage message : history) {
            if (UiMessage.USER.equals(message.role())) {
                String text = message.text();
                if (!text.isBlank()) {
                    messages.add(UserMessage.builder().text(text).build());
                }
            } else {
                for (List<Map<String, Object>> step : steps(message.parts())) {
                    addStep(step, latestData, messages);
                }
            }
        }
        return messages;
    }

    private void addStep(List<Map<String, Object>> step, Map<String, Map<String, Object>> latestData,
                         List<Message> messages) {
        StringBuilder text = new StringBuilder();
        List<AssistantMessage.ToolCall> calls = new ArrayList<>();
        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
        for (Map<String, Object> part : step) {
            String type = String.valueOf(part.get("type"));
            if ("text".equals(type)) {
                text.append(part.get("text"));
            } else if (type.startsWith(TOOL_PREFIX) && hasResult(part)) {
                String name = type.substring(TOOL_PREFIX.length());
                String id = String.valueOf(part.get("toolCallId"));
                calls.add(new AssistantMessage.ToolCall(id, "function", name, json.writeValueAsString(part.get("input"))));
                responses.add(new ToolResponseMessage.ToolResponse(id, name, result(part)));
            } else if (type.startsWith(DATA_PREFIX)) {
                String line = dataLine(part, latestData);
                if (responses.isEmpty()) {
                    text.append(text.isEmpty() ? "" : "\n").append(line);
                } else {
                    ToolResponseMessage.ToolResponse last = responses.removeLast();
                    responses.add(new ToolResponseMessage.ToolResponse(last.id(), last.name(),
                            last.responseData() + "\n" + line));
                }
            }
        }
        if (text.isEmpty() && calls.isEmpty()) {
            return;
        }
        messages.add(AssistantMessage.builder().content(text.toString()).toolCalls(calls).build());
        if (!responses.isEmpty()) {
            messages.add(ToolResponseMessage.builder().responses(responses).build());
        }
    }

    private static boolean hasResult(Map<String, Object> part) {
        return "output-available".equals(part.get("state")) || "output-error".equals(part.get("state"));
    }

    private String result(Map<String, Object> part) {
        return "output-error".equals(part.get("state"))
                ? "Error: " + part.get("errorText")
                : json.writeValueAsString(part.get("output"));
    }

    private String dataLine(Map<String, Object> part, Map<String, Map<String, Object>> latestData) {
        String type = String.valueOf(part.get("type"));
        return latestData.get(dataKey(part)) == part
                ? "[" + type + "] " + json.writeValueAsString(part.get("data"))
                : "[" + type + "] (an earlier version, replaced by a later one below)";
    }

    private static Map<String, Map<String, Object>> latestDataParts(List<UiMessage> history) {
        Map<String, Map<String, Object>> latest = new HashMap<>();
        for (UiMessage message : history) {
            for (Map<String, Object> part : message.parts()) {
                if (String.valueOf(part.get("type")).startsWith(DATA_PREFIX)) {
                    latest.put(dataKey(part), part);
                }
            }
        }
        return latest;
    }

    private static String dataKey(Map<String, Object> part) {
        return part.get("type") + "#" + part.get("id");
    }

    /**
     * {@code step-start} parts separate the steps of one answer.
     */
    private static List<List<Map<String, Object>>> steps(List<Map<String, Object>> parts) {
        List<List<Map<String, Object>>> steps = new ArrayList<>();
        List<Map<String, Object>> current = new ArrayList<>();
        for (Map<String, Object> part : parts) {
            if ("step-start".equals(part.get("type"))) {
                if (!current.isEmpty()) {
                    steps.add(current);
                }
                current = new ArrayList<>();
            } else {
                current.add(part);
            }
        }
        if (!current.isEmpty()) {
            steps.add(current);
        }
        return steps;
    }
}
