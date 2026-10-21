package com.drzewiecki.shitcloud.chat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.stream.Stream;

/**
 * One answer of the chat, streamed as the AI SDK UI message stream. The engine runs the steps itself: each step
 * is one streamed model call; when the model asks for tools, the engine shows the calls, runs the tools, shows
 * the results and starts the next step with them. The model never runs a tool on its own.
 * <p>
 * The answer is saved with the conversation as it was shown, also when the client stopped it half-way or the
 * model failed.
 */
@Service
public class ChatEngine {

    private static final Logger log = LoggerFactory.getLogger(ChatEngine.class);

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final ChatModel model;
    private final ConversationStore store;
    private final ChatSettings settings;
    private final JsonMapper json;
    private final HistoryMapper history;
    private final TaskScheduler scheduler;

    public ChatEngine(ChatModel model, ConversationStore store, ChatSettings settings, JsonMapper json,
                      TaskScheduler scheduler) {
        this.model = model;
        this.store = store;
        this.settings = settings;
        this.json = json;
        this.history = new HistoryMapper(json);
        this.scheduler = scheduler;
    }

    /**
     * @param newConversation the first answer tells the client the conversation's id
     */
    public record Turn(TurnContext context, UiMessage message, boolean newConversation, String systemPrompt,
                       List<ChatTool> tools) {
    }

    public void answer(Turn turn, UiStreamWriter out) {
        Conversation conversation = turn.context().conversation();
        store.append(conversation.id(), turn.message(), null);

        Answer answer = new Answer();
        ScheduledFuture<?> pings = scheduler.scheduleAtFixedRate(() -> ping(out),
                Instant.now().plus(settings.pingInterval()), settings.pingInterval());
        try {
            out.part(UiParts.start(answer.id));
            if (turn.newConversation()) {
                out.part(UiParts.data("conversation", null,
                        Map.of("id", conversation.id(), "title", conversation.title()), true));
            }
            List<UiMessage> saved = store.messages(conversation.id());
            List<Message> messages = prompt(turn, saved);
            ChatOptions options = options(turn.tools());
            TurnContext context = turn.context().with(DataParts.of(saved, answer.parts));
            for (int step = 1; step <= settings.maxSteps(); step++) {
                if (!step(turn, context, messages, options, answer, out)) {
                    break;
                }
            }
            out.part(UiParts.finish());
            out.done();
        } catch (UiStreamWriter.ClientGoneException e) {
            log.info("Chat {}: the client stopped the answer", conversation.id());
        } catch (RuntimeException e) {
            log.warn("Chat {}: the answer failed", conversation.id(), e);
            fail(out, e);
        } finally {
            pings.cancel(false);
            answer.closeText();
            if (answer.hasContent()) {
                store.append(conversation.id(), new UiMessage(answer.id, UiMessage.ASSISTANT, answer.parts),
                        new ConversationStore.Usage(answer.model, answer.inputTokens, answer.outputTokens));
            }
        }
    }

    /**
     * One model call and, if it asked for tools, their runs. False when the answer is complete.
     */
    private boolean step(Turn turn, TurnContext context, List<Message> messages, ChatOptions options, Answer answer,
                         UiStreamWriter out) {
        out.part(UiParts.startStep());
        answer.parts.add(Map.of("type", "step-start"));

        List<AssistantMessage.ToolCall> calls = new ArrayList<>();
        // Closing the stream cancels the model call, also when the client is gone and a write throws.
        // A copy: the list grows with the next steps, the prompt of this one must not.
        try (Stream<ChatResponse> responses = model.stream(new Prompt(List.copyOf(messages), options)).toStream()) {
            responses.forEach(response -> {
                if (response.getResult() == null) {
                    return;
                }
                AssistantMessage output = response.getResult().getOutput();
                String chunk = output.getText();
                if (chunk != null && !chunk.isEmpty()) {
                    if (answer.textId == null) {
                        answer.textId = "t_" + UUID.randomUUID().toString().replace("-", "");
                        out.part(UiParts.textStart(answer.textId));
                    }
                    answer.text.append(chunk);
                    out.part(UiParts.textDelta(answer.textId, chunk));
                }
                calls.addAll(output.getToolCalls());
                answer.count(response);
            });
        }
        String text = answer.text.toString();
        if (answer.textId != null) {
            out.part(UiParts.textEnd(answer.textId));
        }
        answer.closeText();

        if (calls.isEmpty()) {
            out.part(UiParts.finishStep());
            return false;
        }
        messages.add(AssistantMessage.builder().content(text).toolCalls(calls).build());
        List<ToolResponseMessage.ToolResponse> results = new ArrayList<>();
        for (AssistantMessage.ToolCall call : calls) {
            results.add(new ToolResponseMessage.ToolResponse(call.id(), call.name(),
                    runTool(turn, context, call, answer, out)));
        }
        messages.add(ToolResponseMessage.builder().responses(results).build());
        out.part(UiParts.finishStep());
        return true;
    }

    /**
     * @return what the model reads as the tool's result
     */
    private String runTool(Turn turn, TurnContext context, AssistantMessage.ToolCall call, Answer answer,
                           UiStreamWriter out) {
        Map<String, Object> input = input(call);
        out.part(UiParts.toolInputAvailable(call.id(), call.name(), input));
        ChatTool tool = turn.tools().stream().filter(t -> t.name().equals(call.name())).findFirst().orElse(null);

        Map<String, Object> part = new LinkedHashMap<>();
        part.put("type", "tool-" + call.name());
        part.put("toolCallId", call.id());
        part.put("input", tool == null ? input : tool.inputForHistory(input));
        answer.parts.add(part);

        ToolResult result;
        try {
            if (tool == null) {
                throw new ChatTool.ToolException("There is no tool " + call.name());
            }
            result = tool.run(input, context);
        } catch (ChatTool.ToolException e) {
            return toolError(call, e.getMessage(), part, out);
        } catch (RuntimeException e) {
            log.warn("Chat tool {} failed", call.name(), e);
            return toolError(call, "The tool failed on the server", part, out);
        }
        part.put("state", "output-available");
        part.put("output", result.output());
        out.part(UiParts.toolOutputAvailable(call.id(), result.output()));
        for (ToolResult.DataPart data : result.data()) {
            out.part(UiParts.data(data.name(), data.id(), data.data(), false));
            answer.parts.add(UiParts.data(data.name(), data.id(), data.data(), false));
        }
        return result.forModel() != null ? result.forModel() : json.writeValueAsString(result.output());
    }

    private static String toolError(AssistantMessage.ToolCall call, String message, Map<String, Object> part,
                                    UiStreamWriter out) {
        part.put("state", "output-error");
        part.put("errorText", message);
        out.part(UiParts.toolOutputError(call.id(), message));
        return "Error: " + message;
    }

    private Map<String, Object> input(AssistantMessage.ToolCall call) {
        try {
            return call.arguments() == null || call.arguments().isBlank()
                    ? Map.of()
                    : json.readValue(call.arguments(), MAP);
        } catch (JacksonException e) {
            return Map.of("unreadable", call.arguments());
        }
    }

    private List<Message> prompt(Turn turn, List<UiMessage> saved) {
        List<UiMessage> recent = new ArrayList<>(saved.subList(Math.max(0, saved.size() - settings.historyMessages()),
                saved.size()));
        // The model API wants the conversation to open with the user.
        while (!recent.isEmpty() && !UiMessage.USER.equals(recent.getFirst().role())) {
            recent.removeFirst();
        }
        List<Message> messages = new ArrayList<>();
        messages.add(SystemMessage.builder().text(turn.systemPrompt()).build());
        messages.addAll(history.toPrompt(recent));
        return messages;
    }

    /**
     * Starts from the model's own options: a model may ignore generic ones it does not recognise (Anthropic
     * does), losing its configured model name with them.
     */
    private ChatOptions options(List<ChatTool> tools) {
        ChatOptions.Builder<?> builder = model.getOptions().mutate();
        builder.maxTokens(settings.maxTokens());
        if (!(builder instanceof ToolCallingChatOptions.Builder<?> toolOptions)) {
            throw new IllegalStateException("The chat model does not support tools");
        }
        toolOptions.toolCallbacks(tools.stream().map(ChatEngine::definitionOnly).toList());
        return builder.build();
    }

    /**
     * Lets the model know the tool; running it is the engine's job.
     */
    private static ToolCallback definitionOnly(ChatTool tool) {
        ToolDefinition definition = DefaultToolDefinition.builder()
                .name(tool.name())
                .description(tool.description())
                .inputSchema(tool.inputSchema())
                .build();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                throw new UnsupportedOperationException("Chat tools are run by ChatEngine");
            }
        };
    }

    private static void ping(UiStreamWriter out) {
        try {
            out.ping();
        } catch (UiStreamWriter.ClientGoneException e) {
            // The answering thread notices on its next write and stops the model call.
        }
    }

    private static void fail(UiStreamWriter out, RuntimeException e) {
        try {
            out.part(UiParts.error(e.getMessage() == null ? "The answer failed" : e.getMessage()));
            out.done();
        } catch (UiStreamWriter.ClientGoneException gone) {
            // Nobody left to tell.
        }
    }

    /**
     * The answer as saved: parts in the client's shape, the text of the open step, and what it cost.
     */
    private static final class Answer {

        final String id = "m_" + UUID.randomUUID().toString().replace("-", "");
        final List<Map<String, Object>> parts = new ArrayList<>();
        final StringBuilder text = new StringBuilder();
        String textId;
        String model;
        int inputTokens;
        int outputTokens;

        /**
         * The model reports the usage of a call once, with its last chunk.
         */
        void count(ChatResponse response) {
            Usage usage = response.getMetadata().getUsage();
            if (usage != null && usage.getPromptTokens() != null && usage.getPromptTokens() > 0) {
                inputTokens += usage.getPromptTokens();
                outputTokens += usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
            }
            if (response.getMetadata().getModel() != null && !response.getMetadata().getModel().isEmpty()) {
                model = response.getMetadata().getModel();
            }
        }

        void closeText() {
            if (!text.isEmpty()) {
                parts.add(Map.of("type", "text", "text", text.toString()));
                text.setLength(0);
            }
            textId = null;
        }

        boolean hasContent() {
            return parts.stream().anyMatch(part -> !"step-start".equals(part.get("type")));
        }
    }
}
