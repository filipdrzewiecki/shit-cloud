package com.drzewiecki.shitcloud.buildchat;

import com.drzewiecki.shitcloud.chat.ChatTool;
import com.drzewiecki.shitcloud.chat.ToolResult;
import com.drzewiecki.shitcloud.chat.TurnContext;
import com.drzewiecki.shitcloud.service.AppsProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The draft in the preview ({@code data-draft}, fixed id {@code draft}): what {@code save_draft} and
 * {@code edit_draft} produce and check, and where an edit finds the file it changes: the latest draft, else the file
 * an import conversation started from.
 */
@Component
public class Drafts {

    static final String NAME = "draft";

    private final AppsProperties apps;
    private final Uploads uploads;

    public Drafts(AppsProperties apps, Uploads uploads) {
        this.apps = apps;
        this.uploads = uploads;
    }

    /**
     * The conversation's latest draft, also one produced earlier in the answer in progress; before the first draft of
     * an import conversation, the uploaded file.
     */
    Optional<String> latest(TurnContext context) {
        return context.data().latest(NAME, NAME)
                .filter(Map.class::isInstance)
                .map(data -> ((Map<?, ?>) data).get("html"))
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .or(() -> uploads.find(context.conversation().id()).map(Uploads.Upload::html));
    }

    /**
     * @return the file's size in bytes
     */
    long check(String html) {
        if (!html.toLowerCase().contains("<html")) {
            throw new ChatTool.ToolException("html must be a whole HTML document with an <html> element");
        }
        long bytes = html.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > apps.maxHtmlSize().toBytes()) {
            throw new ChatTool.ToolException("The file has " + bytes + " bytes; an app may have at most "
                    + apps.maxHtmlSize().toBytes());
        }
        return bytes;
    }

    ToolResult.DataPart part(String html, TurnContext context) {
        Map<String, Object> draft = new HashMap<>();
        draft.put("html", html);
        draft.put("targetSlug", context.conversation().targetSlug());
        return new ToolResult.DataPart(NAME, NAME, draft);
    }
}
