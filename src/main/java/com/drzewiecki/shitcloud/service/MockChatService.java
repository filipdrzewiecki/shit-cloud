package com.drzewiecki.shitcloud.service;

import com.drzewiecki.shitcloud.model.ChatMessage;
import com.drzewiecki.shitcloud.model.ChatRequest;
import com.drzewiecki.shitcloud.model.ChatResponse;
import org.springframework.stereotype.Service;

/**
 * Canned answers so the build screen can be clicked through before Spring AI and Claude are wired in.
 * Every request returns the same draft app; only the reply text echoes what was asked.
 */
@Service
public class MockChatService {

    private final String draftHtml = SampleAppSeeder.readSample("draft");

    public ChatResponse reply(ChatRequest request) {
        String asked = request.messages() == null || request.messages().isEmpty()
                ? ""
                : request.messages().getLast().content().strip();
        String text = """
                (Mock — no LLM connected yet.)

                Got it: "%s". I've put a simple team list app in the preview. Once the real chat is \
                wired in, this is where I'd explain what I built, which spaces it reads and what data \
                shape it writes.""".formatted(asked);
        return new ChatResponse(new ChatMessage("assistant", text), draftHtml);
    }
}
