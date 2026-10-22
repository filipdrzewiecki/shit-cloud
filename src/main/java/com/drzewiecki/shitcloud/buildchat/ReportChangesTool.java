package com.drzewiecki.shitcloud.buildchat;

import com.drzewiecki.shitcloud.chat.ChatTool;
import com.drzewiecki.shitcloud.chat.ToolResult;
import com.drzewiecki.shitcloud.chat.TurnContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The change list of an adapted file, shown as a card ({@code data-changes}, fixed id: a later report replaces it).
 * One line per change, so the builder sees at a glance what was replaced, moved, removed or will not work.
 */
@Component
public class ReportChangesTool implements ChatTool {

    static final String NAME = "report_changes";
    static final String PART = "changes";
    static final Set<String> KINDS = Set.of("replaced", "moved", "removed", "wont_work");
    static final int MAX_CHANGES = 50;
    static final int MAX_CHARS = 300;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return """
                Shows the user the list of what you changed while adapting an uploaded file, one item per change: \
                replaced (e.g. a CDN library by plain JavaScript), moved (e.g. localStorage data to a collection), \
                removed (e.g. an external font), wont_work (a feature that cannot work on ShitCloud, e.g. a call to \
                an external API). Call it once after the edits, with the complete list; call it again to replace it.""";
    }

    @Override
    public String inputSchema() {
        return """
                {"type": "object",
                 "properties": {"changes": {"type": "array", "minItems": 1, "maxItems": %d,
                   "items": {"type": "object",
                     "properties": {
                       "kind": {"type": "string", "enum": ["replaced", "moved", "removed", "wont_work"]},
                       "what": {"type": "string", "description": "What changed, in a few words"},
                       "why": {"type": "string", "description": "Why, in a few words"}},
                     "required": ["kind", "what"]}}},
                 "required": ["changes"]}""".formatted(MAX_CHANGES);
    }

    @Override
    public ToolResult run(Map<String, Object> input, TurnContext context) {
        if (!(input.get("changes") instanceof List<?> items) || items.isEmpty()) {
            throw new ToolException("changes is missing: pass at least one change");
        }
        if (items.size() > MAX_CHANGES) {
            throw new ToolException("At most " + MAX_CHANGES + " changes; merge similar ones");
        }
        List<Map<String, String>> changes = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            if (!(items.get(i) instanceof Map<?, ?> item)) {
                throw new ToolException("change " + (i + 1) + " is not an object");
            }
            String kind = String.valueOf(item.get("kind"));
            if (!KINDS.contains(kind)) {
                throw new ToolException("change " + (i + 1) + ": kind must be one of replaced, moved, removed, wont_work");
            }
            String what = text(item.get("what"));
            if (what.isEmpty()) {
                throw new ToolException("change " + (i + 1) + ": what is missing");
            }
            Map<String, String> change = new LinkedHashMap<>();
            change.put("kind", kind);
            change.put("what", what);
            change.put("why", text(item.get("why")));
            changes.add(change);
        }
        return ToolResult.of(Map.of("changes", changes.size()),
                new ToolResult.DataPart(PART, PART, Map.of("changes", changes)));
    }

    private static String text(Object value) {
        String text = value == null ? "" : String.valueOf(value).strip();
        return text.length() > MAX_CHARS ? text.substring(0, MAX_CHARS - 1) + "…" : text;
    }
}
