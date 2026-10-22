package com.drzewiecki.shitcloud.chat;

import com.drzewiecki.shitcloud.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The application with {@link FakeChatModel} in place of Claude and a (fake) key, so the chat is available.
 * Every test starts without conversations and with an empty script.
 */
@Import(FakeChatModel.Config.class)
@TestPropertySource(properties = "spring.ai.anthropic.api-key=test-key")
public abstract class ChatIntegrationTest extends IntegrationTest {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    @Autowired
    protected FakeChatModel model;

    @Autowired
    protected JsonMapper json;

    @BeforeEach
    void noConversations() {
        // cascade: other tables point at conversations (data changes, uploaded files), and Postgres refuses to
        // truncate a referenced table on its own even when the referencing one is empty.
        jdbc.sql("truncate chat_conversations, chat_messages cascade").update();
        model.reset();
    }

    /**
     * The parts of an SSE body, in order; comment lines are skipped, {@code [DONE]} becomes {@code {type: "[DONE]"}}.
     */
    protected List<Map<String, Object>> parts(String body) {
        List<Map<String, Object>> parts = new ArrayList<>();
        for (String frame : body.split("\n\n")) {
            if (frame.startsWith(":") || frame.isBlank()) {
                continue;
            }
            if (!frame.startsWith("data: ")) {
                throw new AssertionError("Not an SSE data frame: " + frame);
            }
            String data = frame.substring("data: ".length());
            parts.add("[DONE]".equals(data) ? Map.of("type", "[DONE]") : json.readValue(data, MAP));
        }
        return parts;
    }

    protected static List<Object> types(List<Map<String, Object>> parts) {
        return parts.stream().map(part -> part.get("type")).toList();
    }
}
