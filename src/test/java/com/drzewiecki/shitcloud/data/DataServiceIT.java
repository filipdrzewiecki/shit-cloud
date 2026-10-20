package com.drzewiecki.shitcloud.data;

import com.drzewiecki.shitcloud.IntegrationTest;
import com.drzewiecki.shitcloud.model.DeployRequest;
import com.drzewiecki.shitcloud.db.Timestamps;
import com.drzewiecki.shitcloud.service.AppRegistry;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DataServiceIT extends IntegrationTest {

    private static final String APP = "kalendarz";

    @Autowired
    private DataService data;

    @Autowired
    private AppRegistry appRegistry;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void deployTheApp() {
        appRegistry.deploy(new DeployRequest("Kalendarz", null, "<p>x</p>"), BUILDER_EMAIL);
    }

    @Test
    void set_createsThenUpdates_andKeepsThePreviousState() {
        DocView created = set("filip_2026-10-20", Map.of("type", "office"), "guest:Anna");
        DocView updated = set("filip_2026-10-20", Map.of("type", "off"), "guest:Tomek");

        assertThat(created.version()).isEqualTo(1);
        assertThat(updated.version()).isEqualTo(2);
        assertThat(updated.data()).isEqualTo(Map.of("type", "off"));
        assertThat(updated.createdBy()).isEqualTo("guest:Anna");
        assertThat(updated.updatedBy()).isEqualTo("guest:Tomek");

        List<HistoryRecord> history = history();
        assertThat(history).singleElement().satisfies(entry -> {
            assertThat(entry.op()).isEqualTo("update");
            assertThat(entry.version()).isEqualTo(1);
            assertThat(entry.data()).isEqualTo(Map.of("type", "office"));
            assertThat(entry.updatedBy()).isEqualTo("guest:Anna");
            assertThat(entry.changedBy()).isEqualTo("guest:Tomek");
        });
    }

    @Test
    void insert_writesNoHistory() {
        set("a", Map.of("n", 1), "guest:Anna");

        assertThat(history()).isEmpty();
    }

    @Test
    void remove_keepsTheLastStateInHistory() {
        set("a", Map.of("n", 1), "guest:Anna");

        assertThat(data.remove(APP, "days", "a", null, "guest:Tomek")).isTrue();

        assertThat(find(null)).isEmpty();
        assertThat(history()).singleElement().satisfies(entry -> {
            assertThat(entry.op()).isEqualTo("delete");
            assertThat(entry.data()).isEqualTo(Map.of("n", 1));
            assertThat(entry.changedBy()).isEqualTo("guest:Tomek");
        });
    }

    @Test
    void removingSomethingMissing_isFalse() {
        assertThat(data.remove(APP, "days", "nothing", null, "guest:Anna")).isFalse();
    }

    @Test
    void failedHistoryWrite_rollsTheChangeBack() {
        set("a", Map.of("n", 1), "guest:Anna");
        // Every history insert now fails, after the document was locked but before it is written.
        jdbc.sql("alter table doc_history add constraint no_history check (false) not valid").update();
        try {
            assertThatThrownBy(() -> set("a", Map.of("n", 2), "guest:Tomek")).isNotInstanceOf(DataException.class);
            assertThatThrownBy(() -> data.remove(APP, "days", "a", null, "guest:Tomek"))
                    .isNotInstanceOf(DataException.class);
        } finally {
            jdbc.sql("alter table doc_history drop constraint no_history").update();
        }

        assertThat(find(null)).singleElement().satisfies(doc -> {
            assertThat(doc.version()).isEqualTo(1);
            assertThat(doc.data()).isEqualTo(Map.of("n", 1));
        });
    }

    @Test
    void expectedVersion_guardsAgainstOverwriting() {
        set("a", Map.of("n", 1), "guest:Anna");
        set("a", Map.of("n", 2), "guest:Anna");

        assertThatThrownBy(() -> data.set(APP, "days", "a", new DataWrite(Map.of("n", 3), 1L), "guest:Tomek"))
                .isInstanceOfSatisfying(DataException.class,
                        e -> assertThat(e.code()).isEqualTo(DataException.VERSION_CONFLICT));
        assertThat(data.set(APP, "days", "a", new DataWrite(Map.of("n", 3), 2L), "guest:Tomek").version())
                .isEqualTo(3);
        assertThatThrownBy(() -> data.remove(APP, "days", "a", 2L, "guest:Tomek"))
                .isInstanceOf(DataException.class);
    }

    @Test
    void expectedVersionZero_meansTheDocumentMustNotExistYet() {
        data.set(APP, "days", "a", new DataWrite(Map.of("n", 1), 0L), "guest:Anna");

        assertThatThrownBy(() -> data.set(APP, "days", "a", new DataWrite(Map.of("n", 2), 0L), "guest:Tomek"))
                .isInstanceOf(DataException.class);
    }

    @Test
    void concurrentWritesWithoutVersion_areAllAppliedWithAHistoryEntryEach() throws Exception {
        set("hot", Map.of("n", 0), "guest:Anna");
        int writers = 3;
        int writesEach = 5;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        try {
            List<Future<?>> results = new ArrayList<>();
            for (int w = 0; w < writers; w++) {
                int writer = w;
                Callable<Object> task = () -> {
                    for (int i = 0; i < writesEach; i++) {
                        set("hot", Map.of("n", writer * 100 + i), "guest:W" + writer);
                    }
                    return null;
                };
                results.add(pool.submit(task));
            }
            for (Future<?> result : results) {
                result.get();
            }
        } finally {
            pool.shutdownNow();
        }

        int writes = writers * writesEach;
        assertThat(find(null)).singleElement().satisfies(doc -> assertThat(doc.version()).isEqualTo(writes + 1));
        List<HistoryRecord> history = history();
        assertThat(history).hasSize(writes);
        // Every replaced state is in history exactly once.
        assertThat(history.stream().map(HistoryRecord::version).distinct()).hasSize(writes);
    }

    @Test
    void manyCalls_returnTheirConnections() {
        // The pool in tests holds 4 connections: a call that keeps one would fail here within a few rounds.
        set("a", Map.of("n", 1), "guest:Anna");
        for (int i = 0; i < 25; i++) {
            assertThat(find(Map.of("n", 1))).hasSize(1);
            set("a", Map.of("n", 1), "guest:Anna");
            data.remove(APP, "days", "missing", null, "guest:Anna");
        }
    }

    @Test
    void add_generatesAnId() {
        DocView first = data.add(APP, "votes", new DataWrite(Map.of("place", "Pizza"), null), "guest:Anna");
        DocView second = data.add(APP, "votes", new DataWrite(Map.of("place", "Sushi"), null), "guest:Anna");

        assertThat(first.id()).matches("[0-9a-f]{24}").isNotEqualTo(second.id());
        assertThat(first.version()).isEqualTo(1);
    }

    @Test
    void idAndUnderscoreFields_areNotStored() {
        DocView doc = set("a", Map.of("n", 1, "id", "other", "_version", 7, "_updatedBy", "x"), "guest:Anna");

        assertThat(doc.data()).isEqualTo(Map.of("n", 1));
    }

    @Test
    void query_filtersSortsAndLimitsWithinItsOwnCollection() {
        set("a", Map.of("date", "2026-10-02", "who", "anna"), "guest:Anna");
        set("b", Map.of("date", "2026-10-01", "who", "anna"), "guest:Anna");
        set("c", Map.of("date", "2026-10-03", "who", "tomek"), "guest:Anna");
        data.set(APP, "other", "d", new DataWrite(Map.of("who", "anna"), null), "guest:Anna");

        List<DocView> found = data.query(APP, "days",
                new DataQuery(Map.of("who", "anna"), Map.of("date", 1), null));
        assertThat(found).extracting(DocView::id).containsExactly("b", "a");

        assertThat(data.query(APP, "days", new DataQuery(null, Map.of("date", -1), 1)))
                .extracting(DocView::id).containsExactly("c");
        assertThat(data.query(APP, "days", null)).extracting(DocView::id).containsExactly("a", "b", "c");
    }

    @Test
    void otherAppsDocuments_areNeverReturned() {
        appRegistry.deploy(new DeployRequest("Inna", null, "<p>y</p>"), BUILDER_EMAIL);
        data.set("inna", "days", "a", new DataWrite(Map.of("secret", true), null), "guest:Anna");

        assertThat(data.query(APP, "days", null)).isEmpty();
        assertThat(data.query(APP, "days", new DataQuery(Map.of("space", "inna"), null, null))).isEmpty();
    }

    @Test
    void unknownApp_isRefused() {
        assertThatThrownBy(() -> data.query("no-such-app", "days", null))
                .isInstanceOfSatisfying(DataException.class,
                        e -> assertThat(e.code()).isEqualTo(DataException.NO_SUCH_APP));
    }

    @Test
    void badNamesAndData_areRefused() {
        assertThatThrownBy(() -> data.query(APP, "bad name", null)).isInstanceOf(DataException.class);
        assertThatThrownBy(() -> set("bad id/", Map.of("n", 1), "guest:Anna")).isInstanceOf(DataException.class);
        assertThatThrownBy(() -> set("a", Map.of("a.b", 1), "guest:Anna")).isInstanceOf(DataException.class);
        assertThatThrownBy(() -> set("a", Map.of("x", Map.of("$gt", 1)), "guest:Anna"))
                .isInstanceOf(DataException.class);
        assertThatThrownBy(() -> data.set(APP, "days", "a", new DataWrite(null, null), "guest:Anna"))
                .isInstanceOf(DataException.class);
        assertThatThrownBy(() -> data.query(APP, "days", new DataQuery(null, null, 1001)))
                .isInstanceOf(DataException.class);
    }

    @Test
    void tooLargeDocument_isRefused() {
        String big = "x".repeat(300 * 1024);

        assertThatThrownBy(() -> set("a", Map.of("text", big), "guest:Anna"))
                .isInstanceOfSatisfying(DataException.class,
                        e -> assertThat(e.code()).isEqualTo(DataException.TOO_LARGE));
    }

    private DocView set(String id, Map<String, Object> fields, String caller) {
        return data.set(APP, "days", id, new DataWrite(fields, null), caller);
    }

    private List<DocView> find(Map<String, Object> filter) {
        return data.query(APP, "days", new DataQuery(filter, null, null));
    }

    private List<HistoryRecord> history() {
        return jdbc.sql("select * from doc_history where space = ? order by id").param(APP)
                .query((row, i) -> new HistoryRecord(row.getLong("id"), row.getString("space"),
                        row.getString("collection"), row.getString("doc_id"), row.getLong("version"),
                        row.getString("op"), map(row.getString("data")), row.getString("updated_by"),
                        Timestamps.read(row, "updated_at"), row.getString("changed_by"),
                        Timestamps.read(row, "changed_at")))
                .list();
    }

    private Map<String, Object> map(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
