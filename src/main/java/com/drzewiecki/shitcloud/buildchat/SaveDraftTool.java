package com.drzewiecki.shitcloud.buildchat;

import com.drzewiecki.shitcloud.chat.ChatTool;
import com.drzewiecki.shitcloud.chat.ToolResult;
import com.drzewiecki.shitcloud.chat.TurnContext;
import com.drzewiecki.shitcloud.service.AppsProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Puts a whole app into the preview ({@code data-draft}). The HTML travels once, in the draft part; the saved
 * conversation keeps it there and not a second time in the tool's input.
 */
@Component
public class SaveDraftTool implements ChatTool {

    static final String NAME = "save_draft";

    private final AppsProperties apps;

    public SaveDraftTool(AppsProperties apps) {
        this.apps = apps;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return """
                Shows the app in the live preview next to the chat. Pass the WHOLE HTML file every time \
                (never a fragment or a diff). The user deploys it with a button; this tool does not deploy.""";
    }

    @Override
    public String inputSchema() {
        return """
                {"type": "object",
                 "properties": {"html": {"type": "string", "description": "The complete HTML file, from <!doctype html> to </html>"}},
                 "required": ["html"]}""";
    }

    @Override
    public ToolResult run(Map<String, Object> input, TurnContext context) {
        if (!(input.get("html") instanceof String html) || html.isBlank()) {
            throw new ToolException("html is missing: pass the whole HTML file");
        }
        if (!html.toLowerCase().contains("<html")) {
            throw new ToolException("html must be a whole HTML document with an <html> element");
        }
        long bytes = html.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > apps.maxHtmlSize().toBytes()) {
            throw new ToolException("The file has " + bytes + " bytes; an app may have at most "
                    + apps.maxHtmlSize().toBytes());
        }
        Map<String, Object> draft = new HashMap<>();
        draft.put("html", html);
        draft.put("targetSlug", context.conversation().targetSlug());
        return ToolResult.of(Map.of("saved", true, "bytes", bytes),
                new ToolResult.DataPart("draft", "draft", draft));
    }

    @Override
    public Map<String, Object> inputForHistory(Map<String, Object> input) {
        return Map.of("html", "(the file is in the draft that follows)");
    }
}
