package com.drzewiecki.shitcloud.data;

import com.drzewiecki.shitcloud.db.Timestamps;
import com.drzewiecki.shitcloud.service.AppRegistry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * The Data API: every app's documents in {@code docs}, partitioned by space (the app's slug) and collection.
 * Before a document is changed or removed, its previous state goes to {@code doc_history} in the same
 * transaction, so a write never happens without its history entry.
 *
 * <p>A write first locks the document's row ({@code FOR UPDATE}). Two people changing one document at once are
 * served one after the other, each replacing what the other just wrote, so every replaced state gets exactly one
 * history row and nobody has to retry.
 *
 * <p>The space is trusted as given: it comes from the host page, which takes it from the app it shows, never
 * from the app's message. It must name an existing app.
 */
@Service
public class DataService {

    private static final Pattern COLLECTION = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Pattern DOC_ID = Pattern.compile("[A-Za-z0-9_.:@-]{1,128}");
    private static final int MAX_DATA_DEPTH = 32;
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;
    private final AppRegistry appRegistry;
    private final DataProperties properties;
    private final ObjectMapper json;
    private final RowMapper<DocRecord> docMapper;

    public DataService(JdbcClient jdbc, PlatformTransactionManager transactionManager, AppRegistry appRegistry,
                       DataProperties properties, ObjectMapper json) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
        this.appRegistry = appRegistry;
        this.properties = properties;
        this.json = json;
        this.docMapper = (row, i) -> new DocRecord(
                row.getLong("id"),
                row.getString("space"),
                row.getString("collection"),
                row.getString("doc_id"),
                readJson(row.getString("data")),
                row.getLong("version"),
                row.getString("created_by"),
                Timestamps.read(row, "created_at"),
                row.getString("updated_by"),
                Timestamps.read(row, "updated_at"));
    }

    public List<DocView> query(String space, String collection, DataQuery request) {
        requireCollection(space, collection);
        DataQuery query = request == null ? new DataQuery(null, null, null) : request;

        FilterTranslator.Sql where = FilterTranslator.where(query.filter());
        FilterTranslator.Sql order = FilterTranslator.orderBy(query.sort());
        // Without a sort: oldest first. Always ending on id keeps paging through equal values stable.
        String orderBy = (order.isEmpty() ? "" : order.text() + ", ") + "created_at, id";

        List<Object> params = new ArrayList<>(List.of(space, collection));
        params.addAll(where.params());
        params.addAll(order.params());
        params.add(limit(query.limit()));
        return jdbc.sql("select * from docs where space = ? and collection = ? and " + where.text()
                        + " order by " + orderBy + " limit ?")
                .params(params)
                .query(docMapper)
                .stream()
                .map(DocView::of)
                .toList();
    }

    /**
     * Creates or replaces a document. Without {@code expectedVersion} the last write wins; every replaced state is
     * still kept in history. {@code expectedVersion} 0 means "only if it does not exist yet".
     */
    public DocView set(String space, String collection, String docId, DataWrite write, String caller) {
        requireCollection(space, collection);
        requireDocId(docId);
        Map<String, Object> data = cleanData(write == null ? null : write.data());
        Long expected = write.expectedVersion();
        return transaction.execute(status -> setLocked(space, collection, docId, data, expected, caller));
    }

    /** Creates a document under a new id that sorts by creation time. */
    public DocView add(String space, String collection, DataWrite write, String caller) {
        requireCollection(space, collection);
        Map<String, Object> data = cleanData(write == null ? null : write.data());
        OffsetDateTime now = Timestamps.of(Instant.now());
        return DocView.of(jdbc.sql("""
                        insert into docs (space, collection, doc_id, data, version, created_by, created_at,
                                          updated_by, updated_at)
                        values (?, ?, ?, ?::jsonb, 1, ?, ?, ?, ?)
                        returning *""")
                .params(space, collection, newId(), writeJson(data), caller, now, caller, now)
                .query(docMapper)
                .single());
    }

    /** Removes a document; its last state stays in history. False when there was nothing to remove. */
    public boolean remove(String space, String collection, String docId, Long expectedVersion, String caller) {
        requireCollection(space, collection);
        requireDocId(docId);
        Boolean removed = transaction.execute(status -> {
            DocRecord current = lock(space, collection, docId).orElse(null);
            if (current == null) {
                requireVersion(expectedVersion, 0);
                return false;
            }
            requireVersion(expectedVersion, current.version());
            writeHistory(current, "delete", caller, Instant.now());
            jdbc.sql("delete from docs where id = ?").param(current.id()).update();
            return true;
        });
        return Boolean.TRUE.equals(removed);
    }

    private DocView setLocked(String space, String collection, String docId, Map<String, Object> data,
                              Long expected, String caller) {
        OffsetDateTime now = Timestamps.of(Instant.now());
        Optional<DocRecord> current = lock(space, collection, docId);
        if (current.isEmpty()) {
            requireVersion(expected, 0);
            // A writer creating the same document concurrently makes this insert wait for it, then do nothing;
            // the document then exists and is replaced below like any other.
            Optional<DocRecord> inserted = jdbc.sql("""
                            insert into docs (space, collection, doc_id, data, version, created_by, created_at,
                                              updated_by, updated_at)
                            values (?, ?, ?, ?::jsonb, 1, ?, ?, ?, ?)
                            on conflict (space, collection, doc_id) do nothing
                            returning *""")
                    .params(space, collection, docId, writeJson(data), caller, now, caller, now)
                    .query(docMapper)
                    .optional();
            if (inserted.isPresent()) {
                return DocView.of(inserted.get());
            }
            current = lock(space, collection, docId);
        }
        DocRecord previous = current.orElseThrow();
        requireVersion(expected, previous.version());

        writeHistory(previous, "update", caller, now.toInstant());
        return DocView.of(jdbc.sql("""
                        update docs set data = ?::jsonb, version = version + 1, updated_by = ?, updated_at = ?
                        where id = ?
                        returning *""")
                .params(writeJson(data), caller, now, previous.id())
                .query(docMapper)
                .single());
    }

    /** The document, its row locked until the transaction ends. */
    private Optional<DocRecord> lock(String space, String collection, String docId) {
        return jdbc.sql("select * from docs where space = ? and collection = ? and doc_id = ? for update")
                .params(space, collection, docId)
                .query(docMapper)
                .optional();
    }

    private void writeHistory(DocRecord previous, String op, String changedBy, Instant changedAt) {
        HistoryRecord entry = HistoryRecord.of(previous, op, changedBy, changedAt);
        jdbc.sql("""
                        insert into doc_history (space, collection, doc_id, version, op, data, updated_by,
                                                 updated_at, changed_by, changed_at)
                        values (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)""")
                .params(entry.space(), entry.collection(), entry.docId(), entry.version(), entry.op(),
                        writeJson(entry.data()), entry.updatedBy(), Timestamps.of(entry.updatedAt()),
                        entry.changedBy(), Timestamps.of(entry.changedAt()))
                .update();
    }

    /** 24 hex characters: seconds since the epoch, then randomness — same shape and order as a Mongo ObjectId. */
    static String newId() {
        byte[] random = new byte[8];
        ThreadLocalRandom.current().nextBytes(random);
        return "%08x".formatted(Instant.now().getEpochSecond()) + HexFormat.of().formatHex(random);
    }

    private static void requireVersion(Long expected, long actual) {
        if (expected != null && expected != actual) {
            throw new DataException(HttpStatus.CONFLICT, DataException.VERSION_CONFLICT,
                    "The document was changed by someone else; read it again");
        }
    }

    private int limit(Integer requested) {
        if (requested == null) {
            return properties.maxResults();
        }
        if (requested < 1 || requested > properties.maxResults()) {
            throw DataException.badRequest("limit must be between 1 and " + properties.maxResults());
        }
        return requested;
    }

    private void requireCollection(String space, String collection) {
        if (!COLLECTION.matcher(collection).matches()) {
            throw DataException.badRequest("Collection names are 1-64 letters, digits, _ or -");
        }
        if (!appRegistry.exists(space)) {
            throw new DataException(HttpStatus.NOT_FOUND, DataException.NO_SUCH_APP, "No app " + space);
        }
    }

    private static void requireDocId(String docId) {
        if (!DOC_ID.matcher(docId).matches()) {
            throw DataException.badRequest("Ids are 1-128 letters, digits or _ . : @ -");
        }
    }

    /**
     * The fields to store. {@code id} and fields starting with {@code _} are what reads add to a document, so an
     * app that writes back what it read just works: they are dropped, not refused.
     */
    private Map<String, Object> cleanData(Map<String, Object> data) {
        if (data == null) {
            throw DataException.badRequest("data must be an object");
        }
        Map<String, Object> clean = new LinkedHashMap<>();
        data.forEach((key, value) -> {
            if (!key.equals("id") && !key.startsWith("_")) {
                clean.put(key, value);
            }
        });
        checkNames(clean, 1);
        long size = writeJson(clean).getBytes(StandardCharsets.UTF_8).length;
        if (size > properties.maxDocSize().toBytes()) {
            throw new DataException(HttpStatus.PAYLOAD_TOO_LARGE, DataException.TOO_LARGE,
                    "A document can be at most " + properties.maxDocSize().toKilobytes() + " KB");
        }
        return clean;
    }

    /** Filters cannot address names with dots or a leading $, so they are refused at any depth. */
    private static void checkNames(Object value, int depth) {
        if (depth > MAX_DATA_DEPTH) {
            throw DataException.badRequest("data is nested deeper than " + MAX_DATA_DEPTH + " levels");
        }
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, nested) -> {
                String name = String.valueOf(key);
                if (name.isEmpty() || name.contains(".") || name.startsWith("$")) {
                    throw DataException.badRequest("'" + name + "' is not a valid field name");
                }
                checkNames(nested, depth + 1);
            });
        } else if (value instanceof List<?> list) {
            list.forEach(item -> checkNames(item, depth + 1));
        }
    }

    private String writeJson(Map<String, Object> data) {
        try {
            return json.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            throw DataException.badRequest("data cannot be stored: " + e.getOriginalMessage());
        }
    }

    private Map<String, Object> readJson(String text) {
        try {
            return json.readValue(text, MAP);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored data is not valid JSON", e);
        }
    }
}
