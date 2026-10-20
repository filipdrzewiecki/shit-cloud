package com.drzewiecki.shitcloud.chat;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Stands in for Claude: every {@link #stream} call plays the next scripted step and records the prompt it got.
 * Chunks look like Spring AI's Anthropic model: text chunks first, then one chunk with the tool calls, the usage
 * and the model name.
 */
public class FakeChatModel implements ChatModel {

    public static final String MODEL = "fake-model";

    private final Deque<Flux<ChatResponse>> steps = new ConcurrentLinkedDeque<>();
    private final List<Prompt> prompts = new CopyOnWriteArrayList<>();

    @SafeVarargs
    public final void script(Flux<ChatResponse>... steps) {
        this.steps.addAll(Arrays.asList(steps));
    }

    public void reset() {
        steps.clear();
        prompts.clear();
    }

    public List<Prompt> prompts() {
        return prompts;
    }

    /**
     * A step that writes these text chunks and ends without tool calls.
     */
    public static Flux<ChatResponse> answer(String... chunks) {
        return step(List.of(chunks));
    }

    /**
     * A step that may write text, then asks for tools.
     */
    public static Flux<ChatResponse> step(List<String> chunks, AssistantMessage.ToolCall... calls) {
        List<ChatResponse> responses = new ArrayList<>();
        for (String chunk : chunks) {
            responses.add(new ChatResponse(List.of(new Generation(AssistantMessage.builder().content(chunk).build()))));
        }
        responses.add(end(calls));
        return Flux.fromIterable(responses);
    }

    public static AssistantMessage.ToolCall call(String id, String name, String arguments) {
        return new AssistantMessage.ToolCall(id, "function", name, arguments);
    }

    /**
     * The last chunk of a step: tool calls (if any), 100 input and 10 output tokens.
     */
    public static ChatResponse end(AssistantMessage.ToolCall... calls) {
        AssistantMessage message = AssistantMessage.builder().content("").toolCalls(List.of(calls)).build();
        return ChatResponse.builder()
                .generations(List.of(new Generation(message)))
                .metadata(ChatResponseMetadata.builder().model(MODEL).usage(new DefaultUsage(100, 10)).build())
                .build();
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        prompts.add(prompt);
        Flux<ChatResponse> step = steps.poll();
        return step != null ? step : Flux.error(new IllegalStateException("No scripted step left"));
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        throw new UnsupportedOperationException("The chat only streams");
    }

    @Override
    public ChatOptions getOptions() {
        return ToolCallingChatOptions.builder().model(MODEL).build();
    }

    /**
     * Replaces the real model; also sets no key, so tests that want the chat available set one themselves.
     */
    @TestConfiguration(proxyBeanMethods = false)
    public static class Config {

        @Bean
        @Primary
        public FakeChatModel fakeChatModel() {
            return new FakeChatModel();
        }
    }
}
