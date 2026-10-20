package com.drzewiecki.shitcloud.buildchat;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * The system prompt of the build chat: how to work in the chat, then the builder guide — the same file the GUI
 * shows at {@code /guide} ({@code frontend/src/guide/builder-guide.md}, copied onto the classpath by the build),
 * so the rules apps live by are written down once.
 */
@Component
public class BuildChatPrompt {

    private final String base;

    public BuildChatPrompt() {
        this.base = read("build-chat/chat-rules.md") + "\n\n---\n\n" + read("build-chat/builder-guide.md");
    }

    /**
     * @param targetSlug the app being reworked, or null for a new app
     */
    public String forConversation(String targetSlug) {
        if (targetSlug == null) {
            return base;
        }
        return base + """


                ---

                # This conversation

                The user is changing the existing app `%s`. Call `get_app` with that slug before the first change. \
                Keep its collections and document shapes unless the user asks otherwise: other people's data is \
                stored in them. Save the whole changed file with `save_draft`.""".formatted(targetSlug);
    }

    private static String read(String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Missing " + path + " on the classpath", e);
        }
    }
}
