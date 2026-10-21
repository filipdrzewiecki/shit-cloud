package com.drzewiecki.shitcloud.buildchat;

import com.drzewiecki.shitcloud.chat.ChatTool;
import com.drzewiecki.shitcloud.chat.ToolResult;
import com.drzewiecki.shitcloud.chat.TurnContext;
import com.drzewiecki.shitcloud.data.DataException;
import com.drzewiecki.shitcloud.data.DependencyLog;
import com.drzewiecki.shitcloud.data.DependencyView;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Which apps use a space's data, per collection — so the model can say what a change to a data shape will break
 * before making it. It informs; nothing is blocked.
 */
@Component
public class GetDepsTool implements ChatTool {

    static final String NAME = "get_deps";

    private final DependencyLog dependencies;

    public GetDepsTool(DependencyLog dependencies) {
        this.dependencies = dependencies;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return """
                Lists which apps used the data of a space (an app's slug) in the last 90 days, per collection, \
                reading or writing. Call it before changing or removing fields, collections or document shapes of \
                an app, and name in your reply the other apps that read what you change.""";
    }

    @Override
    public String inputSchema() {
        return """
                {"type": "object",
                 "properties": {"space": {"type": "string", "description": "The app's slug, e.g. team-calendar"}},
                 "required": ["space"]}""";
    }

    @Override
    public ToolResult run(Map<String, Object> input, TurnContext context) {
        if (!(input.get("space") instanceof String space) || space.isBlank()) {
            throw new ToolException("space is missing");
        }
        List<DependencyView> rows;
        try {
            rows = dependencies.users(space);
        } catch (DataException e) {
            throw new ToolException(e.getMessage() + "; see list_spaces");
        }
        long others = rows.stream().map(DependencyView::app).filter(app -> !app.equals(space)).distinct().count();
        return ToolResult.withModelText(Map.of("space", space, "otherApps", others), describe(space, rows));
    }

    /**
     * One line per app: "office-report (Office report): reads marks; writes notes; last used 2026-10-21".
     */
    static String describe(String space, List<DependencyView> rows) {
        Map<String, Map<String, TreeSet<String>>> byApp = new TreeMap<>();
        Map<String, String> labels = new TreeMap<>();
        Map<String, String> lastUsed = new TreeMap<>();
        for (DependencyView row : rows) {
            byApp.computeIfAbsent(row.app(), app -> new TreeMap<>())
                    .computeIfAbsent(row.mode().equals("write") ? "writes" : "reads", mode -> new TreeSet<>())
                    .add(row.collection());
            labels.put(row.app(), row.app() + " (" + row.appName() + (row.archived() ? ", archived" : "") + ")");
            lastUsed.merge(row.app(), row.lastSeenAt().toString().substring(0, 10),
                    (a, b) -> a.compareTo(b) >= 0 ? a : b);
        }
        StringBuilder text = new StringBuilder();
        if (byApp.keySet().stream().noneMatch(app -> !app.equals(space))) {
            text.append("No other app used the data of ").append(space).append(" in the last 90 days.\n");
        } else {
            text.append("Apps that used the data of ").append(space).append(":\n");
        }
        byApp.forEach((app, modes) -> {
            text.append("- ").append(labels.get(app)).append(app.equals(space) ? ", the app itself" : "").append(": ");
            text.append(String.join("; ", modes.entrySet().stream()
                    .map(mode -> mode.getKey() + " " + String.join(", ", mode.getValue())).toList()));
            text.append("; last used ").append(lastUsed.get(app)).append('\n');
        });
        return text.toString().strip();
    }
}
