package com.drzewiecki.shitcloud.chat;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HistoryMapperTest {

    private final HistoryMapper mapper = new HistoryMapper(JsonMapper.builder().build());

    @Test
    void textOnly_mapsToUserAndAssistant() {
        List<Message> messages = mapper.toPrompt(List.of(
                UiMessage.userText("u1", "Hi"),
                answer(step(), text("Hello"))));

        assertThat(messages).extracting(Message::getMessageType).containsExactly(MessageType.USER, MessageType.ASSISTANT);
        assertThat(messages.get(1).getText()).isEqualTo("Hello");
    }

    @Test
    void toolStep_becomesCallsThenResults_andTheFinalTextItsOwnMessage() {
        List<Message> messages = mapper.toPrompt(List.of(
                UiMessage.userText("u1", "Go"),
                answer(step(), text("Looking."), tool("c1", "get_app", Map.of("slug", "a"), Map.of("bytes", 3)),
                        step(), text("Done."))));

        assertThat(messages).extracting(Message::getMessageType)
                .containsExactly(MessageType.USER, MessageType.ASSISTANT, MessageType.TOOL, MessageType.ASSISTANT);
        AssistantMessage asked = (AssistantMessage) messages.get(1);
        assertThat(asked.getText()).isEqualTo("Looking.");
        assertThat(asked.getToolCalls()).containsExactly(
                new AssistantMessage.ToolCall("c1", "function", "get_app", "{\"slug\":\"a\"}"));
        assertThat(((ToolResponseMessage) messages.get(2)).getResponses())
                .containsExactly(new ToolResponseMessage.ToolResponse("c1", "get_app", "{\"bytes\":3}"));
        assertThat(messages.get(3).getText()).isEqualTo("Done.");
    }

    @Test
    void onlyTheLatestDraft_isSentInFull() {
        List<Message> messages = mapper.toPrompt(List.of(
                UiMessage.userText("u1", "Make it"),
                answer(step(), tool("c1", "save_draft", Map.of(), Map.of("saved", true)), draft("<html>v1</html>")),
                UiMessage.userText("u2", "Change it"),
                answer(step(), tool("c2", "save_draft", Map.of(), Map.of("saved", true)), draft("<html>v2</html>"))));

        String first = ((ToolResponseMessage) messages.get(2)).getResponses().getFirst().responseData();
        String second = ((ToolResponseMessage) messages.get(5)).getResponses().getFirst().responseData();
        assertThat(first).doesNotContain("v1").contains("[data-draft] (an earlier version");
        assertThat(second).isEqualTo("{\"saved\":true}\n[data-draft] {\"html\":\"<html>v2</html>\"}");
    }

    @Test
    void aStoppedToolCall_withoutResult_isLeftOut() {
        Map<String, Object> unanswered = Map.of("type", "tool-save_draft", "toolCallId", "c1", "state", "input-available",
                "input", Map.of());

        List<Message> messages = mapper.toPrompt(List.of(UiMessage.userText("u1", "Go"),
                answer(step(), text("Writing…"), unanswered)));

        assertThat(messages).extracting(Message::getMessageType).containsExactly(MessageType.USER, MessageType.ASSISTANT);
        assertThat(((AssistantMessage) messages.get(1)).getToolCalls()).isEmpty();
    }

    @Test
    void toolError_isHandedBackAsAnError() {
        Map<String, Object> failed = Map.of("type", "tool-get_app", "toolCallId", "c1", "state", "output-error",
                "input", Map.of("slug", "x"), "errorText", "There is no app x");

        List<Message> messages = mapper.toPrompt(List.of(UiMessage.userText("u1", "Go"), answer(step(), failed)));

        assertThat(((ToolResponseMessage) messages.get(2)).getResponses().getFirst().responseData())
                .isEqualTo("Error: There is no app x");
    }

    private static UiMessage answer(Map<String, Object>... parts) {
        return new UiMessage("m", UiMessage.ASSISTANT, List.of(parts));
    }

    private static Map<String, Object> step() {
        return Map.of("type", "step-start");
    }

    private static Map<String, Object> text(String text) {
        return Map.of("type", "text", "text", text);
    }

    private static Map<String, Object> tool(String id, String name, Map<String, Object> input, Map<String, Object> output) {
        return Map.of("type", "tool-" + name, "toolCallId", id, "state", "output-available", "input", input,
                "output", output);
    }

    private static Map<String, Object> draft(String html) {
        return Map.of("type", "data-draft", "id", "draft", "data", Map.of("html", html));
    }
}
