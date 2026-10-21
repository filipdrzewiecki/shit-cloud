package com.drzewiecki.shitcloud.buildchat;

import com.drzewiecki.shitcloud.chat.ChatTool;
import com.drzewiecki.shitcloud.chat.ToolResult;
import com.drzewiecki.shitcloud.chat.TurnContext;
import com.drzewiecki.shitcloud.service.AppRegistry;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Changes the current draft with exact-text replacements instead of rewriting the whole file: fewer output tokens,
 * and what the user did not ask to change stays byte for byte. The base is the conversation's latest draft, else
 * the deployed app the conversation reworks. Edits apply in order, all or none.
 */
@Component
public class EditDraftTool implements ChatTool {

    static final String NAME = "edit_draft";

    static final int MAX_EDITS = 50;

    private final Drafts drafts;
    private final AppRegistry registry;

    public EditDraftTool(Drafts drafts, AppRegistry registry) {
        this.drafts = drafts;
        this.registry = registry;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return """
                Changes the current app in the live preview by replacing exact pieces of text. Each old_text must \
                be copied exactly from the current file (whitespace included) and occur in it exactly once — \
                include a few surrounding lines when a snippet repeats. Edits apply in order, each to the result \
                of the previous one; if one fails, none is applied and the error says which and why. The file \
                changed is the latest draft of this conversation, or, before the first draft, the app being \
                reworked (read it with get_app first). Use save_draft only for a new app or a rewrite of most of it.""";
    }

    @Override
    public String inputSchema() {
        return """
                {"type": "object",
                 "properties": {"edits": {"type": "array", "minItems": 1, "maxItems": %d,
                   "items": {"type": "object",
                     "properties": {
                       "old_text": {"type": "string", "description": "Exact text from the current file, occurring once"},
                       "new_text": {"type": "string", "description": "What replaces it; empty removes it"}},
                     "required": ["old_text", "new_text"]}}},
                 "required": ["edits"]}""".formatted(MAX_EDITS);
    }

    @Override
    public ToolResult run(Map<String, Object> input, TurnContext context) {
        List<?> edits = input.get("edits") instanceof List<?> list ? list : List.of();
        if (edits.isEmpty()) {
            throw new ToolException("edits is missing: pass at least one {old_text, new_text}");
        }
        if (edits.size() > MAX_EDITS) {
            throw new ToolException("At most " + MAX_EDITS + " edits at once; use save_draft for a rewrite");
        }
        String html = base(context);
        for (int i = 0; i < edits.size(); i++) {
            html = apply(html, edits.get(i), i + 1);
        }
        long bytes = drafts.check(html);
        return new ToolResult(Map.of("edited", edits.size(), "bytes", bytes),
                "Applied " + edits.size() + " edit(s); the draft in the preview now has " + bytes + " bytes.",
                List.of(drafts.part(html, context)));
    }

    private String base(TurnContext context) {
        return drafts.latest(context).orElseGet(() -> {
            String slug = context.conversation().targetSlug();
            if (slug == null) {
                throw new ToolException("There is no draft yet: create the app with save_draft first");
            }
            try {
                return registry.html(slug);
            } catch (ResponseStatusException e) {
                throw new ToolException("There is no app " + slug + " any more");
            }
        });
    }

    static String apply(String html, Object edit, int number) {
        if (!(edit instanceof Map<?, ?> fields)
                || !(fields.get("old_text") instanceof String oldText)
                || !(fields.get("new_text") instanceof String newText)) {
            throw new ToolException("edit " + number + ": needs old_text and new_text");
        }
        if (oldText.isEmpty()) {
            throw new ToolException("edit " + number + ": old_text is empty");
        }
        if (oldText.equals(newText)) {
            throw new ToolException("edit " + number + ": old_text and new_text are the same");
        }
        int at = html.indexOf(oldText);
        if (at < 0) {
            throw new ToolException("edit " + number + ": old_text not found — copy it exactly from the current "
                    + "file (after the edits before it); nothing was applied");
        }
        int count = occurrences(html, oldText);
        if (count > 1) {
            throw new ToolException("edit " + number + ": old_text found " + count + " times — include more "
                    + "surrounding lines so it occurs once; nothing was applied");
        }
        return html.substring(0, at) + newText + html.substring(at + oldText.length());
    }

    /**
     * Overlapping ones count too: "aa" in "aaa" is ambiguous.
     */
    private static int occurrences(String html, String text) {
        int count = 0;
        for (int at = html.indexOf(text); at >= 0; at = html.indexOf(text, at + 1)) {
            count++;
        }
        return count;
    }
}
