package com.drzewiecki.shitcloud.controller;

import com.drzewiecki.shitcloud.model.ChatRequest;
import com.drzewiecki.shitcloud.model.ChatResponse;
import com.drzewiecki.shitcloud.service.MockChatService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The mock behind today's Build screen, until the Build screen moves to the real build chat
 * ({@code buildchat.BuildChatController}, {@code POST /api/chat}).
 */
@RestController
@RequestMapping("/api/chat/mock")
@PreAuthorize("hasRole('BUILDER')")
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
