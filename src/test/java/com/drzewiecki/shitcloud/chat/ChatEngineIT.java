package com.drzewiecki.shitcloud.chat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.drzewiecki.shitcloud.chat.FakeChatModel.answer;
import static com.drzewiecki.shitcloud.chat.FakeChatModel.call;
import static com.drzewiecki.shitcloud.chat.FakeChatModel.step;
import static org.assertj.core.api.Assertions.assertThat;

class ChatEngineIT extends ChatIntegrationTest {

    private static final String OWNER = "builder@example.com";

    @Autowired
    private ChatEngine engine;

    @Autowired
    private ConversationStore store;

    private Conversation conversation;

    @BeforeEach
    void aConversation() {
        conversation = store.create(OWNER, "Test", null);
    }

    @Test
    void textAnswer_isStreamedAndSaved() {
        model.script(answer("Hello", " there"));

        List<Map<String, Object>> parts = run("Hi", true);

        assertThat(types(parts)).containsExactly("start", "data-conversation", "start-step", "text-start",
                "text-delta", "text-delta", "text-end", "finish-step", "finish", "[DONE]");
        assertThat(parts.get(1)).containsEntry("transient", true)
                .containsEntry("data", Map.of("id", conversation.id(), "title", "Test"));
        assertThat(parts.get(4)).containsEntry("delta", "Hello");
        List<UiMessage> saved = store.messages(conversation.id());
        assertThat(saved).extracting(UiMessage::role).containsExactly("user", "assistant");
        assertThat(saved.get(1).id()).isEqualTo(parts.getFirst().get("messageId"));
        assertThat(saved.get(1).parts()).containsExactly(Map.of("type", "step-start"),
                Map.of("type", "text", "text", "Hello there"));
        assertThat(usage()).containsExactly(FakeChatModel.MODEL, 100, 10);
    }

    @Test
    void continuedConversation_hasNoConversationPart_andResendsHistory() {
        model.script(answer("First"), answer("Second"));
        run("One", true);

        List<Map<String, Object>> parts = run("Two", false);

        assertThat(types(parts)).doesNotContain("data-conversation");
        List<Message> prompt = model.prompts().get(1).getInstructions();
        assertThat(prompt).extracting(Message::getMessageType).containsExactly(MessageType.SYSTEM,
                MessageType.USER, MessageType.ASSISTANT, MessageType.USER);
        assertThat(prompt.get(2).getText()).isEqualTo("First");
        assertThat(prompt.get(3).getText()).isEqualTo("Two");
    }

    @Test
    void toolRound_showsCallResultAndData_thenContinues() {
        model.script(
                step(List.of("Saving."), call("call_1", "echo", "{\"word\":\"<b>\"}")),
                answer("Done."));

        List<Map<String, Object>> parts = run("Go", true);

        assertThat(types(parts)).containsExactly("start", "data-conversation",
                "start-step", "text-start", "text-delta", "text-end",
                "tool-input-available", "tool-output-available", "data-echo", "finish-step",
                "start-step", "text-start", "text-delta", "text-end", "finish-step", "finish", "[DONE]");
        assertThat(parts.get(6)).containsEntry("toolCallId", "call_1").containsEntry("toolName", "echo")
                .containsEntry("input", Map.of("word", "<b>"));
        assertThat(parts.get(7)).containsEntry("output", Map.of("echoed", "<b>"));
        assertThat(parts.get(8)).containsEntry("id", "echo").containsEntry("data", Map.of("word", "<b>"));

        // The second step gets the call and its result.
        List<Message> second = model.prompts().get(1).getInstructions();
        AssistantMessage asked = (AssistantMessage) second.get(second.size() - 2);
        assertThat(asked.getText()).isEqualTo("Saving.");
        assertThat(asked.getToolCalls()).extracting(AssistantMessage.ToolCall::name).containsExactly("echo");
        ToolResponseMessage result = (ToolResponseMessage) second.getLast();
        assertThat(result.getResponses().getFirst().responseData()).isEqualTo("{\"echoed\":\"<b>\"}");

        // Saved in the client's shape, the input as the tool wants it kept.
        List<Map<String, Object>> saved = store.messages(conversation.id()).get(1).parts();
        assertThat(saved).extracting(part -> part.get("type")).containsExactly("step-start", "text", "tool-echo",
                "data-echo", "step-start", "text");
        assertThat(saved.get(2)).containsEntry("state", "output-available")
                .containsEntry("input", Map.of("word", "(kept short)"))
                .containsEntry("output", Map.of("echoed", "<b>"));
        assertThat(usage()).containsExactly(FakeChatModel.MODEL, 200, 20);
    }

    @Test
    void toolFailures_areShownAndHandedToTheModel() {
        model.script(
                step(List.of(), call("c1", "fail", "{}"), call("c2", "nope", "{}"), call("c3", "crash", "{}")),
                answer("Sorry."));

        List<Map<String, Object>> parts = run("Go", true);

        assertThat(parts).filteredOn(part -> "tool-output-error".equals(part.get("type")))
                .extracting(part -> part.get("errorText"))
                .containsExactly("It did not work", "There is no tool nope", "The tool failed on the server");
        ToolResponseMessage results = (ToolResponseMessage) model.prompts().get(1).getInstructions().getLast();
        assertThat(results.getResponses()).extracting(ToolResponseMessage.ToolResponse::responseData)
                .containsExactly("Error: It did not work", "Error: There is no tool nope",
                        "Error: The tool failed on the server");
        assertThat(types(parts)).endsWith("finish", "[DONE]");
    }

    @Test
    void stepLimit_endsTheAnswer() {
        Flux<ChatResponse>[] steps = new Flux[5];
        for (int i = 0; i < steps.length; i++) {
            steps[i] = step(List.of(), call("c" + i, "echo", "{\"word\":\"x\"}"));
        }
        model.script(steps);

        List<Map<String, Object>> parts = run("Loop", true);

        assertThat(model.prompts()).hasSize(5);
        assertThat(types(parts)).filteredOn("start-step"::equals).hasSize(5);
        assertThat(types(parts)).endsWith("finish-step", "finish", "[DONE]");
    }

    @Test
    void promptOptions_carryTheModelsOwnOptions_toolsAndOutputLimit() {
        model.script(answer("ok"));

        run("Hi", true);

        ToolCallingChatOptions options = (ToolCallingChatOptions) model.prompts().getFirst().getOptions();
        assertThat(options.getModel()).isEqualTo(FakeChatModel.MODEL);
        assertThat(options.getMaxTokens()).isEqualTo(32000);
        assertThat(options.getToolCallbacks()).extracting(callback -> callback.getToolDefinition().name())
                .containsExactly("echo", "fail", "crash");
        assertThat(model.prompts().getFirst().getInstructions().getFirst().getText()).isEqualTo("You test.");
    }

    @Test
    void modelFailure_endsWithAnErrorPart_andKeepsWhatWasWritten() {
        // Later than the text, as from the real API: an error already waiting is thrown before queued chunks.
        model.script(Flux.concat(answer("Half").take(1),
                Mono.delay(Duration.ofMillis(50)).then(Mono.error(new IllegalStateException("Overloaded")))));

        List<Map<String, Object>> parts = run("Hi", true);

        assertThat(types(parts)).endsWith("error", "[DONE]");
        assertThat(parts.get(parts.size() - 2)).containsEntry("errorText", "Overloaded");
        assertThat(store.messages(conversation.id()).get(1).parts())
                .contains(Map.of("type", "text", "text", "Half"));
    }

    @Test
    void clientGone_cancelsTheModelCall_andKeepsWhatWasWritten() {
        AtomicBoolean cancelled = new AtomicBoolean();
        model.script(Flux.interval(Duration.ofMillis(5))
                .map(i -> new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("x").build()))))
                .doOnCancel(() -> cancelled.set(true)));

        engine.answer(turn("Hi", false), new UiStreamWriter(new BrokenAfter(6), json));

        assertThat(cancelled).isTrue();
        List<UiMessage> saved = store.messages(conversation.id());
        assertThat(saved).hasSize(2);
        assertThat(saved.get(1).text()).startsWith("x");
    }

    private List<Map<String, Object>> run(String text, boolean isNew) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        engine.answer(turn(text, isNew), new UiStreamWriter(out, json));
        return parts(out.toString(StandardCharsets.UTF_8));
    }

    private ChatEngine.Turn turn(String text, boolean isNew) {
        return new ChatEngine.Turn(new TurnContext(OWNER, conversation), UiMessage.userText("u_" + text, text), isNew,
                "You test.", List.of(new EchoTool(), new FailingTool(), new CrashingTool()));
    }

    private List<Object> usage() {
        return jdbc.sql("select model, input_tokens, output_tokens from chat_messages where role = 'assistant'")
                .query((row, i) -> List.<Object>of(row.getString(1), row.getInt(2), row.getInt(3)))
                .single();
    }

    /**
     * Echoes {@code word}, also as a data part; keeps the input short in history.
     */
    static class EchoTool extends TestTool {

        EchoTool() {
            super("echo");
        }

        @Override
        public ToolResult run(Map<String, Object> input, TurnContext context) {
            return ToolResult.of(Map.of("echoed", input.get("word")),
                    new ToolResult.DataPart("echo", "echo", Map.of("word", input.get("word"))));
        }

        @Override
        public Map<String, Object> inputForHistory(Map<String, Object> input) {
            return Map.of("word", "(kept short)");
        }
    }

    static class FailingTool extends TestTool {

        FailingTool() {
            super("fail");
        }

        @Override
        public ToolResult run(Map<String, Object> input, TurnContext context) {
            throw new ToolException("It did not work");
        }
    }

    static class CrashingTool extends TestTool {

        CrashingTool() {
            super("crash");
        }

        @Override
        public ToolResult run(Map<String, Object> input, TurnContext context) {
            throw new IllegalStateException("secret internals");
        }
    }

    abstract static class TestTool implements ChatTool {

        private final String name;

        TestTool(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return "A test tool";
        }

        @Override
        public String inputSchema() {
            return "{\"type\":\"object\"}";
        }
    }

    /**
     * A client that leaves after a few frames.
     */
    static class BrokenAfter extends OutputStream {

        private int writesLeft;

        BrokenAfter(int writes) {
            this.writesLeft = writes;
        }

        @Override
        public void write(int b) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (writesLeft-- <= 0) {
                throw new IOException("Broken pipe");
            }
        }
    }
}
