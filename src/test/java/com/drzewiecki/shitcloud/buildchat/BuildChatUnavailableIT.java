package com.drzewiecki.shitcloud.buildchat;

import com.drzewiecki.shitcloud.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Without ANTHROPIC_API_KEY the application still starts (dev, tests); the chat says why it cannot answer.
 */
class BuildChatUnavailableIT extends IntegrationTest {

    @Test
    void withoutAKey_theChatAnswers503() throws Exception {
        mvc.perform(post("/api/chat").with(builder()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message": {"id": "u1", "role": "user", "parts": [{"type": "text", "text": "Hi"}]}}"""))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("CHAT_UNAVAILABLE"));
    }
}
