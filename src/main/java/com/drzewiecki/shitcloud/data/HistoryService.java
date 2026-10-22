package com.drzewiecki.shitcloud.data;

import com.drzewiecki.shitcloud.db.Timestamps;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

/**
 * What builders see of an app's stored documents on the Data & history page: its collections, a collection's
 * documents (removed ones too — their last state is in {@code doc_history}) and one document's earlier states.
 * Read only; putting a state back is {@link DataService#restore}.
 */
@Service
public class HistoryService {

    static final int DEFAULT_PAGE = 50;
    static final int MAX_PAGE = 200;
    static final int MAX_VERSIONS = 200;
    static final int PREVIEW_CHARS = 160;
    private static final int MAX_PREFIX = 128;

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final DataService data;
    private final JsonMapper json;

    public HistoryService(JdbcClient jdbc, DataService data, JsonMapper json) {
        this.jdbc = jdbc;
        this.data = data;
        this.json = json;
    }

    /** Every collection with documents now or removed ones still in history, by name. */
    public List<CollectionSummary> collections(String space) {
        data.requireSpace(space);
        return jdbc.sql("""
                        select collection, sum(documents) as documents, sum(removed) as removed from (
                            select collection, count(*) as documents, 0 as removed
                            from docs where space = ? group by collection
                            union all
                            select h.collection, 0, count(distinct h.doc_id)
                            from doc_history h
                            where h.space = ?
                              and not exists (select 1 from docs d where d.space = h.space
                                              and d.collection = h.collection and d.doc_id = h.doc_id)
                            group by h.collection
                        ) counted
                        group by collection
                        order by collection""")
                .params(space, space)
                .query((row, i) -> new CollectionSummary(row.getString("collection"), row.getLong("documents"),
                        row.getLong("removed")))
                .list();
    }

    /**
     * A page of a collection's documents, most recently changed first: the current ones, or with {@code removed} the
     * ones that are gone (newest removal first).
     *
     * @param prefix only ids starting with it; null or empty for all
     */
    public DocPage documents(String space, String collection, boolean removed, String prefix, Integer offset,
                             Integer limit) {
        data.requireCollection(space, collection);
        if (prefix != null && prefix.length() > MAX_PREFIX) {
            throw DataException.badRequest("The id filter is at most " + MAX_PREFIX + " characters");
        }
        int size = limit == null ? DEFAULT_PAGE : limit;
        if (size < 1 || size > MAX_PAGE) {
            throw DataException.badRequest("limit must be between 1 and " + MAX_PAGE);
        }
        int skip = offset == null ? 0 : offset;
        if (skip < 0) {
            throw DataException.badRequest("offset must not be negative");
        }
        String like = like(prefix);

        String sql = removed
                ? """
                select doc_id, version, changed_by as who, changed_at as stamp, left(data::text, ?) as preview from (
                    select distinct on (h.doc_id) h.doc_id, h.version, h.data, h.changed_by, h.changed_at
                    from doc_history h
                    where h.space = ? and h.collection = ? and h.doc_id like ? escape '\\'
                      and not exists (select 1 from docs d where d.space = h.space
                                      and d.collection = h.collection and d.doc_id = h.doc_id)
                    order by h.doc_id, h.id desc
                ) last_states
                order by changed_at desc, doc_id
                limit ? offset ?"""
                : """
                select doc_id, version, updated_by as who, updated_at as stamp, left(data::text, ?) as preview
                from docs
                where space = ? and collection = ? and doc_id like ? escape '\\'
                order by updated_at desc, doc_id
                limit ? offset ?""";
        List<DocSummary> rows = jdbc.sql(sql)
                .params(PREVIEW_CHARS + 1, space, collection, like, size + 1, skip)
                .query((row, i) -> new DocSummary(row.getString("doc_id"), removed, row.getLong("version"),
                        row.getString("who"), Timestamps.read(row, "stamp"), preview(row.getString("preview"))))
                .list();
        boolean hasMore = rows.size() > size;
        return new DocPage(hasMore ? rows.subList(0, size) : rows, hasMore);
    }

    /** A document now (null if removed) and its earlier states, newest first. */
    public DocHistory history(String space, String collection, String docId) {
        data.requireCollection(space, collection);
        DataService.requireDocId(docId);
        DocView current = data.query(space, collection, new DataQuery(Map.of("id", docId), null, 1)).stream()
                .findFirst()
                .orElse(null);
        List<HistoryView> versions = jdbc.sql("""
                        select * from doc_history where space = ? and collection = ? and doc_id = ?
                        order by id desc limit ?""")
                .params(space, collection, docId, MAX_VERSIONS + 1)
                .query((row, i) -> new HistoryView(row.getLong("id"), row.getLong("version"), row.getString("op"),
                        json.readValue(row.getString("data"), MAP), row.getString("updated_by"),
                        Timestamps.read(row, "updated_at"), row.getString("changed_by"),
                        Timestamps.read(row, "changed_at")))
                .list();
        boolean truncated = versions.size() > MAX_VERSIONS;
        return new DocHistory(current, truncated ? versions.subList(0, MAX_VERSIONS) : versions, truncated);
    }

    /** A LIKE pattern matching ids that start with the prefix taken literally. */
    static String like(String prefix) {
        if (prefix == null) {
            return "%";
        }
        return prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    private static String preview(String text) {
        return text.length() > PREVIEW_CHARS ? text.substring(0, PREVIEW_CHARS) + "…" : text;
    }
}
