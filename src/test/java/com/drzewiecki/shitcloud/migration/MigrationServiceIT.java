package com.drzewiecki.shitcloud.migration;

import com.drzewiecki.shitcloud.IntegrationTest;
import com.drzewiecki.shitcloud.data.DataException;
import com.drzewiecki.shitcloud.data.DataQuery;
import com.drzewiecki.shitcloud.data.DataService;
import com.drzewiecki.shitcloud.data.DataWrite;
import com.drzewiecki.shitcloud.data.DocView;
import com.drzewiecki.shitcloud.data.ExportView;
import com.drzewiecki.shitcloud.model.AppSummary;
import com.drzewiecki.shitcloud.model.DeployRequest;
import com.drzewiecki.shitcloud.service.AppRegistry;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assertions.within;

class MigrationServiceIT extends IntegrationTest {

    private static final String SPACE = "team-calendar";
    private static final String OLD_HTML = "<!doctype html><html><body>v1</body></html>";
    private static final String NEW_HTML = "<!doctype html><html><body>v2</body></html>";
    /** type → kind ("off" becomes "absence"); everything else stays. */
    private static final String RENAME = """
            function migrate(doc, collection) {
              if (collection !== 'days') return doc;
              const {type, ...rest} = doc;
              return {...rest, kind: type === 'off' ? 'absence' : type};
            }""";

    @Autowired
    private MigrationService migrations;

    @Autowired
    private DataService data;

    @Autowired
    private AppRegistry registry;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void aCalendarWithData() {
        registry.deploy(new DeployRequest("Team Calendar", null, OLD_HTML), BUILDER_EMAIL);
        set("days", "d1", Map.of("type", "off"));
        set("days", "d2", Map.of("type", "office"));
        set("days", "d3", Map.of("type", "off", "note", "dentist"));
        set("people", "p1", Map.of("name", "Anna"));
    }

    @Test
    void propose_checksEveryDocument_andWritesNothing() {
        MigrationCheck check = migrations.propose(SPACE, List.of("days"), RENAME, "type becomes kind", null, BUILDER_EMAIL);

        assertThat(check.collections()).containsExactly("days");
        assertThat(check.counts()).isEqualTo(new MigrationCounts(3, 3, 0, 0, 0));
        assertThat(check.fields()).isEqualTo(Map.of("days", Map.of(
                "type", new MigrationCheck.FieldChange(0, 3, 0),
                "kind", new MigrationCheck.FieldChange(3, 0, 0))));
        assertThat(check.examples()).hasSize(3).first().satisfies(example -> {
            assertThat(example.before()).isEqualTo(Map.of("type", "off"));
            // id and _ fields the transform passed through are dropped, as on any write.
            assertThat(example.after()).isEqualTo(Map.of("kind", "absence"));
        });
        assertThat(check.failures()).isEmpty();
        assertThat(fields("days", "d1")).isEqualTo(Map.of("type", "off"));
        assertThat(jdbc.sql("select status from migrations where id = ?").param(check.id()).query(String.class).single())
                .isEqualTo("proposed");
        assertThat(jdbc.sql("select count(*) from doc_history").query(Long.class).single()).isZero();
    }

    @Test
    void propose_coversEveryCollectionByDefault_andCountsUnchangedAndRemoved() {
        MigrationCheck check = migrations.propose(SPACE, null, """
                function migrate(doc, collection) {
                  if (collection === 'people') return doc;
                  return doc.type === 'office' ? null : {kind: doc.type, note: doc.note};
                }""", null, null, BUILDER_EMAIL);

        assertThat(check.collections()).containsExactly("days", "people");
        assertThat(check.byCollection()).isEqualTo(Map.of(
                "days", new MigrationCounts(3, 2, 0, 1, 0),
                "people", new MigrationCounts(1, 0, 1, 0, 0)));
        assertThat(check.counts()).isEqualTo(new MigrationCounts(4, 2, 1, 1, 0));
        assertThat(check.examples()).filteredOn(example -> example.after() == null).singleElement()
                .satisfies(example -> assertThat(example.id()).isEqualTo("d2"));
    }

    @Test
    void propose_reportsFailingDocuments_withTheirError() {
        MigrationCheck check = migrations.propose(SPACE, List.of("days"), """
                function migrate(doc) {
                  if (doc.type === 'office') throw new Error('no office here');
                  return {kind: doc.type};
                }""", null, null, BUILDER_EMAIL);

        assertThat(check.counts().failed()).isEqualTo(1);
        assertThat(check.failures()).singleElement().satisfies(failure -> {
            assertThat(failure.id()).isEqualTo("d2");
            assertThat(failure.error()).contains("no office here");
        });
    }

    @Test
    void propose_resultsBreakingTheDataRules_fail() {
        MigrationCheck check = migrations.propose(SPACE, List.of("days"),
                "function migrate(doc) { return {'a.b': 1}; }", null, null, BUILDER_EMAIL);

        assertThat(check.counts().failed()).isEqualTo(3);
        assertThat(check.failures().getFirst().error()).contains("'a.b' is not a valid field name");
    }

    @Test
    void propose_refusesWhatCannotRunAtAll() {
        assertThatThrownBy(() -> migrations.propose(SPACE, List.of("dayz"), RENAME, null, null, BUILDER_EMAIL))
                .isInstanceOf(MigrationException.class).hasMessage("team-calendar has no collection 'dayz'; it has: days, people");
        assertThatThrownBy(() -> migrations.propose("nope", null, RENAME, null, null, BUILDER_EMAIL))
                .isInstanceOf(MigrationException.class).hasMessage("There is no app nope");
        assertThatThrownBy(() -> migrations.propose(SPACE, null, "function migrate(", null, null, BUILDER_EMAIL))
                .isInstanceOf(MigrationException.class).hasMessageStartingWith("The code does not run");
        registry.deploy(new DeployRequest("Empty", null, OLD_HTML), BUILDER_EMAIL);
        assertThatThrownBy(() -> migrations.propose("empty", null, RENAME, null, null, BUILDER_EMAIL))
                .isInstanceOf(MigrationException.class).hasMessage("empty has no data yet; there is nothing to migrate");
    }

    @Test
    void apply_convertsTheDataAndReplacesTheHtml_inOneStep_keepingACopy() {
        long id = propose(RENAME);

        AppSummary app = migrations.applyWithHtml(SPACE, id, NEW_HTML, BUILDER_EMAIL);

        assertThat(app.version()).isEqualTo(2);
        assertThat(registry.html(SPACE)).isEqualTo(NEW_HTML);
        assertThat(fields("days", "d1")).isEqualTo(Map.of("kind", "absence"));
        assertThat(fields("days", "d3")).isEqualTo(Map.of("kind", "absence", "note", "dentist"));
        assertThat(doc("days", "d1").version()).isEqualTo(2);
        assertThat(doc("days", "d1").updatedBy()).isEqualTo(BUILDER_EMAIL);
        // Every replaced state is in history; the copy holds the documents as they were.
        assertThat(jdbc.sql("select op from doc_history").query(String.class).list()).containsOnly("migrate").hasSize(3);
        assertThat(jdbc.sql("select data::text from migration_docs where doc_id = 'd1'").query(String.class).single())
                .contains("\"type\": \"off\"");
        assertThat(jdbc.sql("select count(*) from migration_docs").query(Long.class).single()).isEqualTo(3);

        MigrationView applied = migrations.list(SPACE).getFirst();
        assertThat(applied.status()).isEqualTo("applied");
        assertThat(applied.result()).isEqualTo(new MigrationCounts(3, 3, 0, 0, 0));
        assertThat(applied.htmlVersionBefore()).isEqualTo(1);
        assertThat(applied.htmlVersionAfter()).isEqualTo(2);
        assertThat(applied.appliedBy()).isEqualTo(BUILDER_EMAIL);
        assertThat(applied.copyUntil()).isCloseTo(Instant.now().plus(Duration.ofDays(30)), within(1, ChronoUnit.MINUTES));
        assertThat(applied.undoable()).isTrue();
    }

    @Test
    void apply_alsoConvertsDocumentsWrittenAfterTheCheck() {
        long id = propose(RENAME);
        set("days", "d4", Map.of("type", "off"));

        migrations.applyWithHtml(SPACE, id, NEW_HTML, BUILDER_EMAIL);

        assertThat(fields("days", "d4")).isEqualTo(Map.of("kind", "absence"));
        assertThat(migrations.list(SPACE).getFirst().result().documents()).isEqualTo(4);
    }

    @Test
    void apply_changesNothing_whenADocumentFailsNow() {
        long id = propose("""
                function migrate(doc) {
                  if (doc.broken) throw new Error('cannot read this one');
                  return {kind: doc.type};
                }""");
        set("days", "d5", Map.of("broken", true));

        assertThatThrownBy(() -> migrations.applyWithHtml(SPACE, id, NEW_HTML, BUILDER_EMAIL))
                .isInstanceOfSatisfying(DataException.class, e -> {
                    assertThat(e.code()).isEqualTo(DataException.MIGRATION_FAILED);
                    assertThat(e.getMessage()).contains("days/d5", "cannot read this one", "Nothing was changed");
                });

        assertThat(registry.html(SPACE)).isEqualTo(OLD_HTML);
        assertThat(fields("days", "d1")).isEqualTo(Map.of("type", "off"));
        assertThat(jdbc.sql("select count(*) from migration_docs").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("select count(*) from doc_history").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("select status from migrations where id = ?").param(id).query(String.class).single())
                .isEqualTo("proposed");
    }

    @Test
    void apply_onlyOnce_onlyForItsApp_andNeverAfterAFailedCheck() {
        long id = propose(RENAME);
        registry.deploy(new DeployRequest("Other", null, OLD_HTML), BUILDER_EMAIL);

        assertStale(() -> migrations.applyWithHtml("other", id, NEW_HTML, BUILDER_EMAIL), "There is no proposed data change");
        migrations.applyWithHtml(SPACE, id, NEW_HTML, BUILDER_EMAIL);
        assertStale(() -> migrations.applyWithHtml(SPACE, id, NEW_HTML, BUILDER_EMAIL), "was applied already");

        long failing = propose("function migrate(doc) { throw new Error('no'); }");
        assertStale(() -> migrations.applyWithHtml(SPACE, failing, NEW_HTML, BUILDER_EMAIL), "ask the chat to fix it");
    }

    @Test
    void undo_putsTheDocumentsAndTheHtmlBack_andRemovesWhatWasWrittenSince() {
        long id = propose("""
                function migrate(doc) {
                  return doc.type === 'office' ? null : {kind: doc.type, note: doc.note};
                }""");
        migrations.applyWithHtml(SPACE, id, NEW_HTML, BUILDER_EMAIL);
        set("days", "d1", Map.of("kind", "absence", "note", "changed later"));
        set("days", "d9", Map.of("kind", "new"));

        MigrationUndo undo = migrations.undo(id, BUILDER_EMAIL);

        // d1 and d3 changed back, d2 re-created; d9 did not exist before.
        assertThat(undo).isEqualTo(new MigrationUndo(3, 1, 3));
        assertThat(fields("days", "d1")).isEqualTo(Map.of("type", "off"));
        assertThat(fields("days", "d2")).isEqualTo(Map.of("type", "office"));
        assertThat(fields("days", "d3")).isEqualTo(Map.of("type", "off", "note", "dentist"));
        assertThat(data.query(SPACE, "days", new DataQuery(Map.of("id", "d9"), null, null))).isEmpty();
        // Versions keep counting up, also for the re-created document; nothing replaced is lost.
        assertThat(doc("days", "d2").version()).isEqualTo(2);
        assertThat(doc("days", "d2").createdBy()).isEqualTo("guest:Anna");
        assertThat(jdbc.sql("select doc_id from doc_history where op = 'undo' order by doc_id").query(String.class).list())
                .containsExactly("d1", "d3", "d9");
        assertThat(registry.html(SPACE)).isEqualTo(OLD_HTML);
        assertThat(registry.version(SPACE)).isEqualTo(3);
        // The other collection was not part of the change and is untouched.
        assertThat(fields("people", "p1")).isEqualTo(Map.of("name", "Anna"));

        MigrationView undone = migrations.list(SPACE).getFirst();
        assertThat(undone.status()).isEqualTo("undone");
        assertThat(undone.undoneBy()).isEqualTo(BUILDER_EMAIL);
        assertThat(undone.copyUntil()).isNull();
        assertThat(undone.undoable()).isFalse();
        assertThat(jdbc.sql("select count(*) from migration_docs").query(Long.class).single()).isZero();
        assertStale(() -> migrations.undo(id, BUILDER_EMAIL), "was undone already");
    }

    @Test
    void undo_onlyTheLatestChange_andOnlyWhileItsCopyIsKept() {
        long first = propose(RENAME);
        migrations.applyWithHtml(SPACE, first, NEW_HTML, BUILDER_EMAIL);
        long second = propose("function migrate(doc) { return {...doc, checked: true}; }");
        migrations.applyWithHtml(SPACE, second, NEW_HTML, BUILDER_EMAIL);

        assertThat(migrations.list(SPACE)).extracting(MigrationView::id, MigrationView::undoable)
                .containsExactly(tuple(second, true), tuple(first, false));
        assertStale(() -> migrations.undo(first, BUILDER_EMAIL), "Only the latest data change");

        jdbc.sql("update migrations set copy_until = now() - interval '1 day' where id = ?").param(second).update();
        assertStale(() -> migrations.undo(second, BUILDER_EMAIL), "can no longer be undone");
    }

    @Test
    void unknownChanges_areNotFound() {
        long proposed = propose(RENAME);

        assertThatThrownBy(() -> migrations.undo(proposed, BUILDER_EMAIL))
                .isInstanceOfSatisfying(DataException.class, e -> assertThat(e.code()).isEqualTo(DataException.NO_SUCH_MIGRATION));
        assertThatThrownBy(() -> migrations.copy(999))
                .isInstanceOfSatisfying(DataException.class, e -> assertThat(e.code()).isEqualTo(DataException.NO_SUCH_MIGRATION));
        assertThatThrownBy(() -> migrations.list("nope"))
                .isInstanceOfSatisfying(DataException.class, e -> assertThat(e.code()).isEqualTo(DataException.NO_SUCH_APP));
    }

    @Test
    void copy_holdsTheDocumentsAsTheyWereBefore() {
        long id = propose(RENAME);
        migrations.applyWithHtml(SPACE, id, NEW_HTML, BUILDER_EMAIL);

        ExportView copy = migrations.copy(id);

        assertThat(copy.app().slug()).isEqualTo(SPACE);
        assertThat(copy.app().version()).isEqualTo(1);
        assertThat(copy.collections()).containsOnlyKeys("days");
        assertThat(copy.collections().get("days")).extracting(DocView::id).containsExactlyInAnyOrder("d1", "d2", "d3");
        assertThat(copy.collections().get("days")).allSatisfy(doc -> assertThat(doc.data()).containsKey("type"));
    }

    @Test
    void writesToTheApp_waitWhileItsDataIsBeingChanged_otherAppsDoNot() throws Exception {
        registry.deploy(new DeployRequest("Other", null, OLD_HTML), BUILDER_EMAIL);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        CompletableFuture<Void> migration = CompletableFuture.runAsync(() -> transaction.executeWithoutResult(status -> {
            data.lockSpace(SPACE);
            locked.countDown();
            await(release);
        }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<DocView> write = CompletableFuture.supplyAsync(() ->
                data.set(SPACE, "days", "d7", new DataWrite(Map.of("type", "off"), null), "guest:Anna"));
        data.set("other", "notes", "n1", new DataWrite(Map.of("x", 1), null), "guest:Anna");
        Thread.sleep(300);
        assertThat(write).isNotDone();

        release.countDown();
        migration.get(10, TimeUnit.SECONDS);
        assertThat(write.get(10, TimeUnit.SECONDS).id()).isEqualTo("d7");
    }

    private long propose(String code) {
        return migrations.propose(SPACE, List.of("days"), code, null, null, BUILDER_EMAIL).id();
    }

    private void set(String collection, String id, Map<String, Object> fields) {
        data.set(SPACE, collection, id, new DataWrite(fields, null), "guest:Anna");
    }

    private DocView doc(String collection, String id) {
        return data.query(SPACE, collection, new DataQuery(Map.of("id", id), null, null)).getFirst();
    }

    private Map<String, Object> fields(String collection, String id) {
        return doc(collection, id).data();
    }

    private static void assertStale(ThrowingCallable call, String message) {
        assertThatThrownBy(call).isInstanceOfSatisfying(DataException.class, e -> {
            assertThat(e.code()).isEqualTo(DataException.MIGRATION_STALE);
            assertThat(e.getMessage()).contains(message);
        });
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
