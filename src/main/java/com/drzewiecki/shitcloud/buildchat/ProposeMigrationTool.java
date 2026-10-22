package com.drzewiecki.shitcloud.buildchat;

import com.drzewiecki.shitcloud.chat.ChatTool;
import com.drzewiecki.shitcloud.chat.ToolResult;
import com.drzewiecki.shitcloud.chat.TurnContext;
import com.drzewiecki.shitcloud.data.DependencyLog;
import com.drzewiecki.shitcloud.data.DependencyView;
import com.drzewiecki.shitcloud.migration.MigrationCheck;
import com.drzewiecki.shitcloud.migration.MigrationCounts;
import com.drzewiecki.shitcloud.migration.MigrationException;
import com.drzewiecki.shitcloud.migration.MigrationService;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Converts the stored documents of the app being changed to a new shape: checks the model's transform over every
 * document and shows the result as a card ({@code data-migration}, fixed id: a new proposal replaces the card). Nothing
 * is written here; the builder applies it together with the draft by replacing the app's HTML.
 */
@Component
public class ProposeMigrationTool implements ChatTool {

    static final String NAME = "propose_migration";
    static final String PART = "migration";

    private final MigrationService migrations;
    private final DependencyLog dependencies;
    private final JsonMapper json;

    public ProposeMigrationTool(MigrationService migrations, DependencyLog dependencies, JsonMapper json) {
        this.migrations = migrations;
        this.dependencies = dependencies;
        this.json = json;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return """
                Converts the stored documents of the app being changed to the shape the new draft expects. Pass \
                JavaScript source defining function migrate(doc, collection): doc is a document as the SDK returns it \
                (its fields plus id, _version, _updatedBy, _updatedAt); return the document's new fields (id and _ \
                fields are ignored, the id stays) or null to remove the document. Same collection, same id. Plain \
                JavaScript only: no Java, no network, no files; it must be deterministic (no Date.now(), \
                Math.random()) and must not drop information the user did not ask to drop. Every document is checked \
                now and nothing is written; the result shows the counts, field changes, examples and failures. The \
                user applies it together with the draft by replacing the app's HTML. Propose again to replace it.""";
    }

    @Override
    public String inputSchema() {
        return """
                {"type": "object",
                 "properties": {
                   "code": {"type": "string", "description": "function migrate(doc, collection) { ... }"},
                   "summary": {"type": "string", "description": "One line for the user: what changes in the data"},
                   "collections": {"type": "array", "items": {"type": "string"},
                                   "description": "The collections to convert; omit for all of the app's collections"}},
                 "required": ["code", "summary"]}""";
    }

    @Override
    public ToolResult run(Map<String, Object> input, TurnContext context) {
        String space = context.conversation().targetSlug();
        if (space == null) {
            throw new ToolException("propose_migration converts the data of an existing app being changed; this "
                    + "conversation creates a new app, which has no data yet");
        }
        if (!(input.get("code") instanceof String code) || code.isBlank()) {
            throw new ToolException("code is missing: pass function migrate(doc, collection) { ... }");
        }
        String summary = input.get("summary") instanceof String text && !text.isBlank() ? text.strip() : null;
        List<String> collections = new ArrayList<>();
        if (input.get("collections") instanceof List<?> list) {
            list.forEach(item -> collections.add(String.valueOf(item)));
        }

        MigrationCheck check;
        try {
            check = migrations.propose(space, collections, code, summary, context.conversation().id(),
                    context.caller());
        } catch (MigrationException e) {
            throw new ToolException(e.getMessage());
        }
        List<Reader> readers = readers(space, check.collections());
        MigrationCounts counts = check.counts();
        Map<String, Object> output = Map.of("migration", check.id(), "documents", counts.documents(),
                "changed", counts.changed(), "removed", counts.removed(), "failed", counts.failed());
        return new ToolResult(output, describe(check, readers, json), List.of(part(check, readers)));
    }

    /** Other apps that read the migrated collections; they will likely break. */
    private List<Reader> readers(String space, List<String> collections) {
        Map<String, Reader> byApp = new TreeMap<>();
        for (DependencyView row : dependencies.users(space)) {
            if (!row.app().equals(space) && row.mode().equals("read") && collections.contains(row.collection())) {
                byApp.computeIfAbsent(row.app(), app -> new Reader(app, row.appName(), new TreeSet<>()))
                        .collections().add(row.collection());
            }
        }
        return List.copyOf(byApp.values());
    }

    private ToolResult.DataPart part(MigrationCheck check, List<Reader> readers) {
        Map<String, Object> data = new HashMap<>();
        data.put("id", check.id());
        data.put("space", check.space());
        data.put("collections", check.collections());
        data.put("summary", check.summary());
        data.put("counts", check.counts());
        data.put("byCollection", check.byCollection());
        data.put("fields", check.fields());
        data.put("examples", check.examples().stream().map(example -> {
            Map<String, Object> shown = new LinkedHashMap<>();
            shown.put("collection", example.collection());
            shown.put("id", example.id());
            shown.put("before", SampleDocsTool.shorten(example.before()));
            shown.put("after", example.after() == null ? null : SampleDocsTool.shorten(example.after()));
            return shown;
        }).toList());
        data.put("failures", check.failures());
        data.put("readers", readers);
        return new ToolResult.DataPart(PART, PART, data);
    }

    /** What the model reads: the whole check, documents cut like {@code sample_docs}. */
    static String describe(MigrationCheck check, List<Reader> readers, JsonMapper json) {
        MigrationCounts counts = check.counts();
        StringBuilder text = new StringBuilder();
        text.append("Checked migration ").append(check.id()).append(" of ").append(check.space())
                .append(" — nothing is written yet.\n");
        text.append("Documents: ").append(line(counts)).append('\n');
        if (check.byCollection().size() > 1) {
            check.byCollection().forEach((collection, n) ->
                    text.append("- ").append(collection).append(": ").append(line(n)).append('\n'));
        }
        if (!check.fields().isEmpty()) {
            text.append("Field changes:\n");
            check.fields().forEach((collection, fields) -> {
                List<String> changes = new ArrayList<>();
                fields.forEach((field, change) -> {
                    if (change.added() > 0) changes.add(field + " added to " + change.added());
                    if (change.removed() > 0) changes.add(field + " removed from " + change.removed());
                    if (change.changed() > 0) changes.add(field + " changed in " + change.changed());
                });
                text.append("- ").append(collection).append(": ").append(String.join("; ", changes)).append('\n');
            });
        }
        if (!check.examples().isEmpty()) {
            text.append("Examples (before → after):\n");
            check.examples().forEach(example -> text.append("- ").append(example.collection()).append('/')
                    .append(example.id()).append(": ").append(SampleDocsTool.cut(example.before(), json)).append(" → ")
                    .append(example.after() == null ? "removed" : SampleDocsTool.cut(example.after(), json))
                    .append('\n'));
        }
        if (!check.failures().isEmpty()) {
            text.append("Failures (first ").append(check.failures().size()).append(" of ").append(counts.failed())
                    .append("):\n");
            check.failures().forEach(failure -> text.append("- ").append(failure.collection()).append('/')
                    .append(failure.id()).append(": ").append(failure.error()).append('\n'));
        }
        if (!readers.isEmpty()) {
            text.append("Other apps read this data and will likely break: ");
            text.append(String.join(", ", readers.stream()
                    .map(r -> r.app() + " (reads " + String.join(", ", r.collections()) + ")").toList()));
            text.append('\n');
        }
        text.append(counts.failed() > 0
                ? "It cannot be applied while documents fail: fix the code and call propose_migration again."
                : "The user applies it with the button that replaces the app's HTML with the draft, so the draft must "
                + "already read the new shape. Tell the user in a few lines what will change in the data"
                + (counts.removed() > 0 ? ", and say clearly that " + counts.removed() + " document(s) will be removed" : "")
                + ".");
        return text.toString();
    }

    private static String line(MigrationCounts n) {
        return n.documents() + " — " + n.changed() + " changed, " + n.unchanged() + " unchanged, " + n.removed()
                + " removed, " + n.failed() + " failed";
    }

    record Reader(String app, String name, TreeSet<String> collections) {
    }
}
