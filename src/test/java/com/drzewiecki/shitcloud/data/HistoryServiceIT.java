package com.drzewiecki.shitcloud.data;

import com.drzewiecki.shitcloud.IntegrationTest;
import com.drzewiecki.shitcloud.model.DeployRequest;
import com.drzewiecki.shitcloud.service.AppRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HistoryServiceIT extends IntegrationTest {

    private static final String SPACE = "team-calendar";

    @Autowired
    private HistoryService history;

    @Autowired
    private DataService data;

    @Autowired
    private AppRegistry registry;

    @BeforeEach
    void aCalendar() {
        registry.deploy(new DeployRequest("Team Calendar", null, "<p>x</p>"), BUILDER_EMAIL);
    }

    @Test
    void collections_countCurrentAndRemovedDocuments_alsoCollectionsOnlyHistoryKnows() {
        set("days", "d1", Map.of("type", "off"), "guest:Anna");
        set("days", "d2", Map.of("type", "off"), "guest:Anna");
        data.remove(SPACE, "days", "d2", null, "guest:Piotr");
        set("notes", "n1", Map.of("text", "x"), "guest:Anna");
        data.remove(SPACE, "notes", "n1", null, "guest:Anna");

        assertThat(history.collections(SPACE)).containsExactly(
                new CollectionSummary("days", 1, 1),
                new CollectionSummary("notes", 0, 1));
    }

    @Test
    void documents_newestChangeFirst_pagedAndFilteredByIdPrefix() {
        set("days", "anna_1", Map.of("n", 1), "guest:Anna");
        set("days", "anna_2", Map.of("n", 2), "guest:Anna");
        set("days", "piotr_1", Map.of("n", 3), "guest:Piotr");
        set("days", "anna_1", Map.of("n", 4), "guest:Anna");
        // Fixed times: writes in one test can share a timestamp.
        changedAt("anna_2", "2026-10-20T10:00:00Z");
        changedAt("piotr_1", "2026-10-21T10:00:00Z");
        changedAt("anna_1", "2026-10-22T10:00:00Z");

        DocPage all = history.documents(SPACE, "days", false, null, null, null);
        assertThat(all.items()).extracting(DocSummary::id).containsExactly("anna_1", "piotr_1", "anna_2");
        assertThat(all.hasMore()).isFalse();
        assertThat(all.items().getFirst()).satisfies(doc -> {
            assertThat(doc.removed()).isFalse();
            assertThat(doc.version()).isEqualTo(2);
            assertThat(doc.updatedBy()).isEqualTo("guest:Anna");
            assertThat(doc.preview()).isEqualTo("{\"n\": 4}");
        });

        DocPage first = history.documents(SPACE, "days", false, null, 0, 2);
        assertThat(first.items()).hasSize(2);
        assertThat(first.hasMore()).isTrue();
        DocPage second = history.documents(SPACE, "days", false, null, 2, 2);
        assertThat(second.items()).extracting(DocSummary::id).containsExactly("anna_2");
        assertThat(second.hasMore()).isFalse();

        assertThat(history.documents(SPACE, "days", false, "anna", null, null).items())
                .extracting(DocSummary::id).containsExactly("anna_1", "anna_2");
    }

    @Test
    void documents_prefixIsTakenLiterally() {
        set("days", "a_b", Map.of("n", 1), "guest:Anna");
        set("days", "axb", Map.of("n", 2), "guest:Anna");

        assertThat(history.documents(SPACE, "days", false, "a_", null, null).items())
                .extracting(DocSummary::id).containsExactly("a_b");
        assertThat(history.documents(SPACE, "days", false, "%", null, null).items()).isEmpty();
    }

    @Test
    void documents_removed_listsTheLastStateAndWhoRemovedIt() {
        set("days", "d1", Map.of("type", "off"), "guest:Anna");
        set("days", "d1", Map.of("type", "office"), "guest:Anna");
        data.remove(SPACE, "days", "d1", null, "guest:Piotr");
        set("days", "d2", Map.of("type", "off"), "guest:Anna");

        DocPage removed = history.documents(SPACE, "days", true, null, null, null);

        assertThat(removed.items()).singleElement().satisfies(doc -> {
            assertThat(doc.id()).isEqualTo("d1");
            assertThat(doc.removed()).isTrue();
            assertThat(doc.version()).isEqualTo(2);
            assertThat(doc.updatedBy()).isEqualTo("guest:Piotr");
            assertThat(doc.preview()).contains("office");
        });
    }

    @Test
    void documents_longDocumentsAreShortenedInTheList() {
        set("days", "d1", Map.of("text", "x".repeat(500)), "guest:Anna");

        String preview = history.documents(SPACE, "days", false, null, null, null).items().getFirst().preview();

        assertThat(preview).hasSize(HistoryService.PREVIEW_CHARS + 1).endsWith("…");
    }

    @Test
    void documents_refuseBadInput() {
        assertThatThrownBy(() -> history.documents(SPACE, "days", false, null, null, 0))
                .isInstanceOfSatisfying(DataException.class, e -> assertThat(e.code()).isEqualTo(DataException.BAD_REQUEST));
        assertThatThrownBy(() -> history.documents(SPACE, "days", false, null, -1, null))
                .isInstanceOf(DataException.class);
        assertThatThrownBy(() -> history.documents(SPACE, "bad name", false, null, null, null))
                .isInstanceOf(DataException.class);
        assertThatThrownBy(() -> history.collections("nope"))
                .isInstanceOfSatisfying(DataException.class, e -> assertThat(e.code()).isEqualTo(DataException.NO_SUCH_APP));
    }

    @Test
    void history_showsTheDocumentNow_andItsEarlierStatesNewestFirst() {
        set("days", "d1", Map.of("type", "office"), "guest:Anna");
        set("days", "d1", Map.of("type", "off"), "guest:Piotr");

        DocHistory doc = history.history(SPACE, "days", "d1");

        assertThat(doc.current().data()).isEqualTo(Map.of("type", "off"));
        assertThat(doc.current().version()).isEqualTo(2);
        assertThat(doc.versions()).singleElement().satisfies(state -> {
            assertThat(state.version()).isEqualTo(1);
            assertThat(state.op()).isEqualTo("update");
            assertThat(state.data()).isEqualTo(Map.of("type", "office"));
            assertThat(state.updatedBy()).isEqualTo("guest:Anna");
            assertThat(state.changedBy()).isEqualTo("guest:Piotr");
        });
        assertThat(doc.truncated()).isFalse();

        data.remove(SPACE, "days", "d1", null, "guest:Ola");
        DocHistory removed = history.history(SPACE, "days", "d1");
        assertThat(removed.current()).isNull();
        assertThat(removed.versions()).extracting(HistoryView::op).containsExactly("delete", "update");
    }

    @Test
    void restore_makesTheEarlierStateTheNextVersion_keepingTheReplacedOneInHistory() {
        set("days", "d1", Map.of("type", "office"), "guest:Anna");
        set("days", "d1", Map.of("type", "off"), "guest:Piotr");
        long first = historyId("d1", 1);

        DocView restored = data.restore(SPACE, "days", "d1", first, 2, BUILDER_EMAIL);

        assertThat(restored.version()).isEqualTo(3);
        assertThat(restored.data()).isEqualTo(Map.of("type", "office"));
        assertThat(restored.updatedBy()).isEqualTo(BUILDER_EMAIL);
        assertThat(history.history(SPACE, "days", "d1").versions().getFirst()).satisfies(state -> {
            assertThat(state.op()).isEqualTo("restore");
            assertThat(state.version()).isEqualTo(2);
            assertThat(state.data()).isEqualTo(Map.of("type", "off"));
            assertThat(state.changedBy()).isEqualTo(BUILDER_EMAIL);
        });
    }

    @Test
    void restore_isRefused_whenTheDocumentChangedSinceTheBuilderLooked() {
        set("days", "d1", Map.of("type", "office"), "guest:Anna");
        set("days", "d1", Map.of("type", "off"), "guest:Piotr");
        long first = historyId("d1", 1);
        set("days", "d1", Map.of("type", "sick"), "guest:Piotr");

        assertThatThrownBy(() -> data.restore(SPACE, "days", "d1", first, 2, BUILDER_EMAIL))
                .isInstanceOfSatisfying(DataException.class, e -> assertThat(e.code()).isEqualTo(DataException.VERSION_CONFLICT));
        assertThat(history.history(SPACE, "days", "d1").current().data()).isEqualTo(Map.of("type", "sick"));
    }

    @Test
    void restore_bringsARemovedDocumentBack_withItsVersionContinuing() {
        set("days", "d1", Map.of("type", "office"), "guest:Anna");
        set("days", "d1", Map.of("type", "off"), "guest:Anna");
        data.remove(SPACE, "days", "d1", null, "guest:Piotr");
        long first = historyId("d1", 1);

        assertThatThrownBy(() -> data.restore(SPACE, "days", "d1", first, 2, BUILDER_EMAIL))
                .isInstanceOfSatisfying(DataException.class, e -> assertThat(e.code()).isEqualTo(DataException.VERSION_CONFLICT));
        DocView back = data.restore(SPACE, "days", "d1", first, 0, BUILDER_EMAIL);

        assertThat(back.data()).isEqualTo(Map.of("type", "office"));
        assertThat(back.version()).isEqualTo(3);
        assertThat(back.createdBy()).isEqualTo(BUILDER_EMAIL);
        assertThat(history.documents(SPACE, "days", true, null, null, null).items()).isEmpty();
    }

    @Test
    void restore_onlyStatesOfThatDocument() {
        set("days", "d1", Map.of("type", "office"), "guest:Anna");
        set("days", "d1", Map.of("type", "off"), "guest:Anna");
        set("days", "d2", Map.of("type", "off"), "guest:Anna");
        long ofD1 = historyId("d1", 1);

        assertThatThrownBy(() -> data.restore(SPACE, "days", "d2", ofD1, 1, BUILDER_EMAIL))
                .isInstanceOfSatisfying(DataException.class, e -> assertThat(e.code()).isEqualTo(DataException.NO_SUCH_VERSION));
    }

    @Test
    void like_escapesWildcards() {
        assertThat(HistoryService.like(null)).isEqualTo("%");
        assertThat(HistoryService.like("a_b%c\\d")).isEqualTo("a\\_b\\%c\\\\d%");
    }

    private void set(String collection, String id, Map<String, Object> fields, String caller) {
        data.set(SPACE, collection, id, new DataWrite(fields, null), caller);
    }

    private void changedAt(String docId, String at) {
        jdbc.sql("update docs set updated_at = ?::timestamptz where doc_id = ?").params(at, docId).update();
    }

    private long historyId(String docId, long version) {
        List<Long> ids = jdbc.sql("select id from doc_history where doc_id = ? and version = ?")
                .params(docId, version).query(Long.class).list();
        return ids.getFirst();
    }
}
