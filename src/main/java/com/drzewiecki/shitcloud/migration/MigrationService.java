package com.drzewiecki.shitcloud.migration;

import com.drzewiecki.shitcloud.data.DataException;
import com.drzewiecki.shitcloud.data.DataService;
import com.drzewiecki.shitcloud.data.DocRecord;
import com.drzewiecki.shitcloud.data.DocView;
import com.drzewiecki.shitcloud.data.ExportView;
import com.drzewiecki.shitcloud.db.Timestamps;
import com.drzewiecki.shitcloud.model.AppSummary;
import com.drzewiecki.shitcloud.service.AppRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiConsumer;

/**
 * Data migrations written by the build chat: an app's documents converted in place by a JavaScript transform
 * ({@link MigrationSandbox}). A proposal is checked first — the transform runs over every document and nothing is
 * written — and the builder approves on the counts and examples. Applying runs the transform again over the
 * documents as they are then, so documents written in between are converted too.
 */
@Service
public class MigrationService {

    static final int PAGE = 500;
    static final int MAX_EXAMPLES = 3;
    static final int MAX_FAILURES = 5;

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;
    private final DataService data;
    private final AppRegistry appRegistry;
    private final MigrationSandbox sandbox;
    private final MigrationProperties properties;
    private final JsonMapper json;

    public MigrationService(JdbcClient jdbc, PlatformTransactionManager transactionManager, DataService data,
                            AppRegistry appRegistry, MigrationSandbox sandbox, MigrationProperties properties,
                            JsonMapper json) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
        this.data = data;
        this.appRegistry = appRegistry;
        this.sandbox = sandbox;
        this.properties = properties;
        this.json = json;
    }

    /**
     * Checks a transform over the app's documents and records it as proposed.
     *
     * @param requested the collections to migrate; null or empty for every collection the app has
     * @throws MigrationException when it cannot run at all (no such app or collection, too many documents, code that
     *                            does not compile, out of time); failures of single documents are in the check
     */
    public MigrationCheck propose(String space, List<String> requested, String code, String summary,
                                  String conversationId, String caller) {
        if (code == null || code.isBlank()) {
            throw new MigrationException("code is missing: pass function migrate(doc, collection) { … }");
        }
        if (!appRegistry.exists(space)) {
            throw new MigrationException("There is no app " + space);
        }
        List<String> collections = collectionsOf(space, requested);
        long documents = data.count(space, collections);
        if (documents == 0) {
            throw new MigrationException(space + " has no documents in " + String.join(", ", collections)
                    + "; there is nothing to migrate");
        }
        if (documents > properties.maxDocuments()) {
            throw new MigrationException(space + " has " + documents + " documents; a migration converts at most "
                    + properties.maxDocuments());
        }

        Map<String, Tally> tallies = new TreeMap<>();
        Map<String, Map<String, long[]>> fields = new TreeMap<>();
        List<MigrationCheck.Example> examples = new ArrayList<>();
        List<MigrationCheck.Failure> failures = new ArrayList<>();
        walk(space, collections, code, (doc, step) -> {
            tallies.computeIfAbsent(doc.collection(), c -> new Tally()).add(step);
            switch (step) {
                case Change change -> {
                    countFields(fields.computeIfAbsent(doc.collection(), c -> new TreeMap<>()), doc.data(), change.data());
                    if (examples.size() < MAX_EXAMPLES) {
                        examples.add(new MigrationCheck.Example(doc.collection(), doc.docId(), doc.data(), change.data()));
                    }
                }
                case Remove remove -> {
                    if (examples.size() < MAX_EXAMPLES) {
                        examples.add(new MigrationCheck.Example(doc.collection(), doc.docId(), doc.data(), null));
                    }
                }
                case Fail fail -> {
                    if (failures.size() < MAX_FAILURES) {
                        failures.add(new MigrationCheck.Failure(doc.collection(), doc.docId(), fail.error()));
                    }
                }
                case Unchanged unchanged -> {
                }
            }
        });

        Map<String, MigrationCounts> byCollection = new LinkedHashMap<>();
        collections.forEach(c -> byCollection.put(c, tallies.getOrDefault(c, new Tally()).counts()));
        MigrationCounts counts = byCollection.values().stream().reduce(MigrationCounts.NONE, MigrationCounts::plus);
        Map<String, Map<String, MigrationCheck.FieldChange>> fieldChanges = new LinkedHashMap<>();
        fields.forEach((collection, byField) -> {
            Map<String, MigrationCheck.FieldChange> changes = new LinkedHashMap<>();
            byField.forEach((field, n) -> changes.put(field, new MigrationCheck.FieldChange(n[0], n[1], n[2])));
            fieldChanges.put(collection, changes);
        });

        long id = jdbc.sql("""
                        insert into migrations (space, conversation_id, collections, summary, code, status, checked,
                                                proposed_by, proposed_at)
                        values (?, ?, ?, ?, ?, 'proposed', ?::jsonb, ?, ?)
                        returning id""")
                .params(space, conversationId, collections.toArray(String[]::new), summary, code,
                        json.writeValueAsString(counts), caller, Timestamps.of(Instant.now()))
                .query(Long.class)
                .single();
        return new MigrationCheck(id, space, collections, summary, counts, byCollection, fieldChanges, examples,
                failures);
    }

    /**
     * Replaces the app's HTML and converts its data with a checked proposal, in one transaction: either both happen
     * or neither. Writes to the app wait meanwhile. The documents as they were are copied first and kept for
     * {@code migration.copy-ttl}, for undo.
     *
     * @throws DataException {@code MIGRATION_STALE} when the proposal is not one of this app's, was applied already
     *                       or failed its check; {@code MIGRATION_FAILED} when the transform fails on the documents as
     *                       they are now (nothing is changed then)
     */
    public AppSummary applyWithHtml(String space, long migrationId, String html, String caller) {
        return transaction.execute(status -> {
            data.lockSpace(space);
            Proposal proposal = jdbc.sql("select space, collections, code, status, checked from migrations where id = ? for update")
                    .param(migrationId)
                    .query((row, i) -> new Proposal(row.getString("space"),
                            List.of((String[]) row.getArray("collections").getArray()), row.getString("code"),
                            row.getString("status"), json.readValue(row.getString("checked"), MigrationCounts.class)))
                    .optional()
                    .filter(p -> p.space().equals(space))
                    .orElseThrow(() -> stale("There is no proposed data change " + migrationId + " for " + space));
            if (!proposal.status().equals("proposed")) {
                throw stale("Data change " + migrationId + " was " + proposal.status() + " already");
            }
            if (proposal.checked().failed() > 0) {
                throw stale("Data change " + migrationId + " failed on " + proposal.checked().failed()
                        + " document(s) when checked; ask the chat to fix it");
            }
            long documents = data.count(space, proposal.collections());
            if (documents > properties.maxDocuments()) {
                throw failed(space + " now has " + documents + " documents; a migration converts at most "
                        + properties.maxDocuments());
            }

            // The HTML first: a file that is too large fails before any document is touched.
            int versionBefore = appRegistry.version(space);
            AppSummary app = appRegistry.replaceHtml(space, html, caller);

            Instant now = Instant.now();
            jdbc.sql("""
                            insert into migration_docs (migration_id, collection, doc_id, data, version, created_by,
                                                        created_at, updated_by, updated_at)
                            select ?, collection, doc_id, data, version, created_by, created_at, updated_by, updated_at
                            from docs where space = ? and collection = any(?)""")
                    .params(migrationId, space, proposal.collections().toArray(String[]::new))
                    .update();

            Tally tally = new Tally();
            try {
                walk(space, proposal.collections(), proposal.code(), (doc, step) -> {
                    tally.add(step);
                    switch (step) {
                        case Change change -> data.rewrite(doc, change.data(), "migrate", caller, now);
                        case Remove remove -> data.erase(doc, "migrate", caller, now);
                        case Fail fail -> throw failed("The data change fails on " + doc.collection() + "/"
                                + doc.docId() + " (written after the check?): " + fail.error()
                                + ". Nothing was changed; ask the chat to fix it");
                        case Unchanged unchanged -> {
                        }
                    }
                });
            } catch (MigrationException e) {
                throw failed(e.getMessage() + ". Nothing was changed");
            }

            jdbc.sql("""
                            update migrations set status = 'applied', result = ?::jsonb, html_version_before = ?,
                                   html_version_after = ?, applied_by = ?, applied_at = ?, copy_until = ?
                            where id = ?""")
                    .params(json.writeValueAsString(tally.counts()), versionBefore, app.version(), caller,
                            Timestamps.of(now), Timestamps.of(now.plus(properties.copyTtl())), migrationId)
                    .update();
            return app;
        });
    }

    /**
     * The data changes applied to an app (undone ones included), newest first.
     */
    public List<MigrationView> list(String space) {
        if (!appRegistry.exists(space)) {
            throw new DataException(HttpStatus.NOT_FOUND, DataException.NO_SUCH_APP, "No app " + space);
        }
        return jdbc.sql(VIEW + " where m.space = ? and m.status <> 'proposed' order by m.id desc")
                .param(space)
                .query(this::view)
                .list();
    }

    /**
     * Puts the app back as it was right before the data change: its documents in the migrated collections exactly as
     * copied then (documents written since are removed; every replaced state goes to history as {@code undo}), and the
     * HTML of the version before. Only the app's latest applied change, while its copy is kept.
     */
    public MigrationUndo undo(long migrationId, String caller) {
        return transaction.execute(status -> {
            data.lockSpace(find(migrationId).space());
            // Read again under the lock: another undo or apply may have finished meanwhile.
            MigrationView migration = find(migrationId);
            if (!migration.undoable()) {
                throw stale(migration.status().equals("undone")
                        ? "Data change " + migrationId + " was undone already"
                        : migration.copyUntil() == null || migration.copyUntil().isBefore(Instant.now())
                        ? "The copy of the data before change " + migrationId + " is gone; it can no longer be undone"
                        : "Only the latest data change of " + migration.space() + " can be undone");
            }
            String space = migration.space();
            String[] collections = migration.collections().toArray(String[]::new);
            OffsetDateTime now = Timestamps.of(Instant.now());

            // Every state about to be replaced or removed goes to history first; documents equal to their copy stay.
            jdbc.sql("""
                            insert into doc_history (space, collection, doc_id, version, op, data, updated_by,
                                                     updated_at, changed_by, changed_at)
                            select d.space, d.collection, d.doc_id, d.version, 'undo', d.data, d.updated_by,
                                   d.updated_at, ?, ?
                            from docs d
                            where d.space = ? and d.collection = any(?)
                              and not exists (select 1 from migration_docs c
                                              where c.migration_id = ? and c.collection = d.collection
                                                and c.doc_id = d.doc_id and c.data = d.data)""")
                    .params(caller, now, space, collections, migrationId)
                    .update();
            int removed = jdbc.sql("""
                            delete from docs d
                            where d.space = ? and d.collection = any(?)
                              and not exists (select 1 from migration_docs c
                                              where c.migration_id = ? and c.collection = d.collection
                                                and c.doc_id = d.doc_id)""")
                    .params(space, collections, migrationId)
                    .update();
            int changedBack = jdbc.sql("""
                            update docs d set data = c.data, version = d.version + 1, updated_by = ?, updated_at = ?
                            from migration_docs c
                            where c.migration_id = ? and d.space = ? and d.collection = c.collection
                              and d.doc_id = c.doc_id and d.data <> c.data""")
                    .params(caller, now, migrationId, space)
                    .update();
            // Versions continue after the highest one history knows, so they never repeat for one id.
            int recreated = jdbc.sql("""
                            insert into docs (space, collection, doc_id, data, version, created_by, created_at,
                                              updated_by, updated_at)
                            select ?, c.collection, c.doc_id, c.data,
                                   greatest(c.version, coalesce((select max(h.version) from doc_history h
                                                                 where h.space = ? and h.collection = c.collection
                                                                   and h.doc_id = c.doc_id), 0)) + 1,
                                   c.created_by, c.created_at, ?, ?
                            from migration_docs c
                            where c.migration_id = ?
                              and not exists (select 1 from docs d where d.space = ? and d.collection = c.collection
                                                                     and d.doc_id = c.doc_id)""")
                    .params(space, space, caller, now, migrationId, space)
                    .update();

            Integer htmlVersion = null;
            if (migration.htmlVersionBefore() != null) {
                htmlVersion = appRegistry.html(space, migration.htmlVersionBefore())
                        .map(html -> appRegistry.replaceHtml(space, html, caller).version())
                        .orElse(null);
            }
            jdbc.sql("update migrations set status = 'undone', undone_by = ?, undone_at = ?, copy_until = null where id = ?")
                    .params(caller, now, migrationId)
                    .update();
            jdbc.sql("delete from migration_docs where migration_id = ?").param(migrationId).update();
            return new MigrationUndo(changedBack + recreated, removed, htmlVersion);
        });
    }

    /**
     * The documents of a data change's collections as they were right before it, in the shape of the data download.
     */
    public ExportView copy(long migrationId) {
        MigrationView migration = find(migrationId);
        if (migration.copyUntil() == null || migration.copyUntil().isBefore(Instant.now())) {
            throw new DataException(HttpStatus.NOT_FOUND, DataException.NO_SUCH_MIGRATION,
                    "The copy of the data before change " + migrationId + " is gone");
        }
        String name = jdbc.sql("select name from apps where slug = ?").param(migration.space()).query(String.class).single();
        Map<String, List<DocView>> collections = new LinkedHashMap<>();
        jdbc.sql("select * from migration_docs where migration_id = ? order by collection, created_at, doc_id")
                .param(migrationId)
                .query((row, i) -> {
                    DocView doc = new DocView(row.getString("doc_id"), json.readValue(row.getString("data"), MAP),
                            row.getLong("version"), row.getString("created_by"), Timestamps.read(row, "created_at"),
                            row.getString("updated_by"), Timestamps.read(row, "updated_at"));
                    collections.computeIfAbsent(row.getString("collection"), c -> new ArrayList<>()).add(doc);
                    return doc;
                })
                .list();
        int version = migration.htmlVersionBefore() == null ? 0 : migration.htmlVersionBefore();
        return new ExportView(new ExportView.App(migration.space(), name, version), migration.appliedAt(), collections);
    }

    private MigrationView find(long migrationId) {
        return jdbc.sql(VIEW + " where m.id = ? and m.status <> 'proposed'")
                .param(migrationId)
                .query(this::view)
                .optional()
                .orElseThrow(() -> new DataException(HttpStatus.NOT_FOUND, DataException.NO_SUCH_MIGRATION,
                        "No applied data change " + migrationId));
    }

    /** A change is undoable while its copy is kept and no later change of the app is applied. */
    private static final String VIEW = """
            select m.*,
                   m.status = 'applied' and m.copy_until > now()
                   and not exists (select 1 from migrations later
                                   where later.space = m.space and later.status = 'applied' and later.id > m.id)
                   as undoable
            from migrations m""";

    private MigrationView view(ResultSet row, int i) throws SQLException {
        String result = row.getString("result");
        return new MigrationView(
                row.getLong("id"),
                row.getString("space"),
                row.getString("status"),
                List.of((String[]) row.getArray("collections").getArray()),
                row.getString("summary"),
                json.readValue(row.getString("checked"), MigrationCounts.class),
                result == null ? null : json.readValue(result, MigrationCounts.class),
                row.getObject("html_version_before", Integer.class),
                row.getObject("html_version_after", Integer.class),
                row.getString("applied_by"),
                Timestamps.read(row, "applied_at"),
                row.getString("undone_by"),
                Timestamps.read(row, "undone_at"),
                Timestamps.read(row, "copy_until"),
                row.getBoolean("undoable"));
    }

    private record Proposal(String space, List<String> collections, String code, String status,
                            MigrationCounts checked) {
    }

    private static DataException stale(String message) {
        return new DataException(HttpStatus.CONFLICT, DataException.MIGRATION_STALE, message);
    }

    private static DataException failed(String message) {
        return new DataException(HttpStatus.CONFLICT, DataException.MIGRATION_FAILED, message);
    }

    /** The requested collections, all of them checked to exist; or every collection the app has. */
    private List<String> collectionsOf(String space, List<String> requested) {
        List<String> existing = data.collections(space);
        if (requested == null || requested.isEmpty()) {
            if (existing.isEmpty()) {
                throw new MigrationException(space + " has no data yet; there is nothing to migrate");
            }
            return existing;
        }
        List<String> collections = requested.stream().distinct().toList();
        for (String collection : collections) {
            if (!existing.contains(collection)) {
                throw new MigrationException(space + " has no collection '" + collection + "'; it has: "
                        + (existing.isEmpty() ? "none" : String.join(", ", existing)));
            }
        }
        return collections;
    }

    /** Runs the transform over every document of the collections, page by page, in storage order. */
    private void walk(String space, List<String> collections, String code, BiConsumer<DocRecord, Step> each) {
        try (MigrationSandbox.Run run = sandbox.open(code)) {
            long after = 0;
            List<DocRecord> page;
            do {
                page = data.page(space, collections, after, PAGE);
                for (DocRecord doc : page) {
                    each.accept(doc, step(doc, run.migrate(asApps(doc), doc.collection())));
                }
                if (!page.isEmpty()) {
                    after = page.getLast().id();
                }
            } while (page.size() == PAGE);
        }
    }

    /** What the transform's result means for the stored document, after the same rules as any app write. */
    private Step step(DocRecord doc, MigrationSandbox.Outcome outcome) {
        return switch (outcome) {
            case MigrationSandbox.Removed removed -> new Remove();
            case MigrationSandbox.Failed failed -> new Fail(failed.error());
            case MigrationSandbox.Converted converted -> {
                try {
                    Map<String, Object> clean = data.cleanData(converted.data());
                    yield clean.equals(doc.data()) ? new Unchanged() : new Change(clean);
                } catch (DataException e) {
                    yield new Fail(e.getMessage());
                }
            }
        };
    }

    /** The document as the app's SDK returns it, which is what the model saw in {@code sample_docs}. */
    static Map<String, Object> asApps(DocRecord doc) {
        Map<String, Object> flat = new LinkedHashMap<>();
        flat.put("id", doc.docId());
        flat.putAll(doc.data());
        flat.put("_version", doc.version());
        flat.put("_updatedBy", doc.updatedBy());
        flat.put("_updatedAt", doc.updatedAt().toString());
        return flat;
    }

    /** Per field: [added, removed, changed]. */
    private static void countFields(Map<String, long[]> byField, Map<String, Object> before, Map<String, Object> after) {
        Set<String> names = new HashSet<>(before.keySet());
        names.addAll(after.keySet());
        for (String name : names) {
            boolean had = before.containsKey(name);
            boolean has = after.containsKey(name);
            if (had && has && Objects.equals(before.get(name), after.get(name))) {
                continue;
            }
            byField.computeIfAbsent(name, n -> new long[3])[had && has ? 2 : has ? 0 : 1]++;
        }
    }

    sealed interface Step {
    }

    record Unchanged() implements Step {
    }

    record Change(Map<String, Object> data) implements Step {
    }

    record Remove() implements Step {
    }

    record Fail(String error) implements Step {
    }

    private static final class Tally {

        private long changed;
        private long unchanged;
        private long removed;
        private long failed;

        void add(Step step) {
            switch (step) {
                case Change change -> changed++;
                case Unchanged unchanged -> this.unchanged++;
                case Remove remove -> removed++;
                case Fail fail -> failed++;
            }
        }

        MigrationCounts counts() {
            return new MigrationCounts(changed + unchanged + removed + failed, changed, unchanged, removed, failed);
        }
    }
}
