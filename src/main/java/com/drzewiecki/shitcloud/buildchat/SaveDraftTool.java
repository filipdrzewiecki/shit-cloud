package com.drzewiecki.shitcloud.buildchat;

import com.drzewiecki.shitcloud.chat.ChatTool;
import com.drzewiecki.shitcloud.chat.ToolResult;
import com.drzewiecki.shitcloud.chat.TurnContext;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Puts a whole app into the preview ({@code data-draft}). The HTML travels once, in the draft part; the saved
 * conversation keeps it there and not a second time in the tool's input.
 */
@Component
public class SaveDraftTool implements ChatTool {

    static final String NAME = "save_draft";

    private final Drafts drafts;

    public SaveDraftTool(Drafts drafts) {
        this.drafts = drafts;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return """
                Shows the app in the live preview next to the chat. Pass the WHOLE HTML file, never a fragment. \
                Use it for the first version of an app and for a rewrite of most of it; change an existing draft \
                with edit_draft instead. The user deploys it with a button; this tool does not deploy.""";
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
        long bytes = drafts.check(html);
        return ToolResult.of(Map.of("saved", true, "bytes", bytes), drafts.part(html, context));
    }

    @Override
    public Map<String, Object> inputForHistory(Map<String, Object> input) {
        return Map.of("html", "(the file is in the draft that follows)");
    }
}
