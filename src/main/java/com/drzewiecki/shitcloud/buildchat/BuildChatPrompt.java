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

                The user is changing the existing app `%1$s`, whose data lives in the space `%1$s`. \
                Unless this conversation already has a draft of it, call `get_app` with that slug first — and again \
                in a later message if you have not made a draft yet, as its source is not kept between messages. \
                Then change it with `edit_draft`; until the first draft, the edits apply to the deployed app. \
                Before touching code that reads or writes data, look at the app's documents with `sample_docs` \
                (`list_spaces` names its collections). Keep its collections and document shapes unless the user \
                asks otherwise: other people's data is stored in them, and other apps may read it — call \
                `get_deps` with `%1$s` before changing how its data is stored. When the change does need the stored \
                documents in a new shape, convert them with `propose_migration` once the draft reads the new \
                shape.""".formatted(targetSlug);
    }

    private static String read(String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Missing " + path + " on the classpath", e);
        }
    }
}
