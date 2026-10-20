package com.drzewiecki.shitcloud.controller;

import com.drzewiecki.shitcloud.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ChatControllerIT extends IntegrationTest {

    private static final String MESSAGE = """
            {"messages": [{"role": "user", "content": "Zrób listę obecności"}]}""";

    @Test
    void guest_cannotUseTheChat() throws Exception {
        mvc.perform(post("/api/chat/mock").with(guest("Anna"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MESSAGE))
                .andExpect(status().isForbidden());
    }

    @Test
    void builder_getsAReply() throws Exception {
        mvc.perform(post("/api/chat/mock").with(builder())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MESSAGE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply.role").value("assistant"));
    }
}
