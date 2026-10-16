package com.drzewiecki.shitcloud.controller;

import com.drzewiecki.shitcloud.model.ChatRequest;
import com.drzewiecki.shitcloud.model.ChatResponse;
import com.drzewiecki.shitcloud.service.MockChatService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Plain request/response for now. The real endpoint will stream over SSE from the separate chat module.
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final MockChatService chatService;

    public ChatController(MockChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping
    public ChatResponse chat(@RequestBody ChatRequest request) {
        return chatService.reply(request);
    }
}
