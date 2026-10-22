package com.drzewiecki.shitcloud.migration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The transform runs in GraalJS with no way out; these run the real engine (slow on the first test: the engine is
 * built then).
 */
class MigrationSandboxTest {

    private MigrationSandbox sandbox = sandbox(Duration.ofSeconds(30), 100_000);

    @AfterEach
    void shutdown() {
        sandbox.shutdown();
    }

    @Test
    void convertsADocument_seeingItAsAppsDo() {
        try (MigrationSandbox.Run run = sandbox.open("""
                function migrate(doc, collection) {
                  const {type, ...rest} = doc;
                  return {...rest, kind: type === 'off' ? 'absence' : type, seen: doc.id + '/' + doc._version + '/' + collection};
                }""")) {
            MigrationSandbox.Outcome outcome = run.migrate(doc("d1", Map.of("type", "off", "n", 2)), "days");

            assertThat(outcome).isInstanceOf(MigrationSandbox.Converted.class);
            Map<String, Object> data = ((MigrationSandbox.Converted) outcome).data();
            assertThat(data).containsEntry("kind", "absence").containsEntry("n", 2).containsEntry("seen", "d1/3/days")
                    .doesNotContainKey("type");
        }
    }

    @Test
    void null_removesTheDocument() {
        try (MigrationSandbox.Run run = sandbox.open("function migrate(doc) { return doc.old ? null : doc; }")) {
            assertThat(run.migrate(doc("d1", Map.of("old", true)), "days")).isInstanceOf(MigrationSandbox.Removed.class);
            assertThat(run.migrate(doc("d2", Map.of("old", false)), "days")).isInstanceOf(MigrationSandbox.Converted.class);
        }
    }

    @Test
    void errorsAndWrongResults_failThatDocumentOnly() {
        try (MigrationSandbox.Run run = sandbox.open("""
                function migrate(doc) {
                  if (doc.kind === 'throw') throw new Error('boom');
                  if (doc.kind === 'text') return 'text';
                  if (doc.kind === 'list') return [1];
                  if (doc.kind === 'nothing') return;
                  return doc;
                }""")) {
            assertThat(error(run.migrate(doc("a", Map.of("kind", "throw")), "c"))).contains("boom").contains("(line 2)");
            assertThat(error(run.migrate(doc("b", Map.of("kind", "text")), "c")))
                    .contains("migrate must return an object or null, not string");
            assertThat(error(run.migrate(doc("c", Map.of("kind", "list")), "c"))).contains("not an array");
            assertThat(error(run.migrate(doc("d", Map.of("kind", "nothing")), "c"))).contains("not undefined");
            assertThat(run.migrate(doc("e", Map.of("kind", "fine")), "c")).isInstanceOf(MigrationSandbox.Converted.class);
        }
    }

    @Test
    void anEndlessLoop_failsItsDocument_andTheNextOneStillRuns() {
        sandbox.shutdown();
        sandbox = sandbox(Duration.ofSeconds(30), 10_000);
        try (MigrationSandbox.Run run = sandbox.open("function migrate(doc) { while (doc.loop) {} return doc; }")) {
            assertThat(error(run.migrate(doc("a", Map.of("loop", true)), "c"))).contains("more than 10000 statements");
            assertThat(run.migrate(doc("b", Map.of("loop", false)), "c")).isInstanceOf(MigrationSandbox.Converted.class);
        }
    }

    @Test
    void theScript_cannotReachJavaFilesOrModules() {
        try (MigrationSandbox.Run run = sandbox.open("""
                function migrate(doc) {
                  return {java: typeof Java, polyglot: typeof Polyglot, load: typeof load, require: typeof require,
                          process: typeof process};
                }""")) {
            Map<String, Object> data = ((MigrationSandbox.Converted) run.migrate(doc("a", Map.of()), "c")).data();

            assertThat(data).containsOnly(Map.entry("java", "undefined"), Map.entry("polyglot", "undefined"),
                    Map.entry("load", "undefined"), Map.entry("require", "undefined"), Map.entry("process", "undefined"));
        }
    }

    @Test
    void codeThatDoesNotCompileOrDefinesNoMigrate_isRefusedAtOnce() {
        assertThatThrownBy(() -> sandbox.open("function migrate(doc) {"))
                .isInstanceOf(MigrationException.class).hasMessageStartingWith("The code does not run: SyntaxError");
        assertThatThrownBy(() -> sandbox.open("const migrated = 1;"))
                .isInstanceOf(MigrationException.class).hasMessage("The code must define function migrate(doc, collection)");
    }

    @Test
    void aRunOutOfTime_failsAsAWhole() {
        sandbox.shutdown();
        sandbox = sandbox(Duration.ofMillis(500), Long.MAX_VALUE);
        try (MigrationSandbox.Run run = sandbox.open("function migrate(doc) { while (true) {} }")) {
            assertThatThrownBy(() -> run.migrate(doc("a", Map.of()), "c"))
                    .isInstanceOf(MigrationException.class).hasMessageContaining("took longer than");
            assertThatThrownBy(() -> run.migrate(doc("b", Map.of()), "c"))
                    .isInstanceOf(MigrationException.class).hasMessageContaining("took longer than");
        }
    }

    private static MigrationSandbox sandbox(Duration timeout, long statementLimit) {
        return new MigrationSandbox(new MigrationProperties(1000, timeout, statementLimit, Duration.ofDays(30),
                Duration.ofDays(30)), JsonMapper.builder().build());
    }

    private static Map<String, Object> doc(String id, Map<String, Object> fields) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("id", id);
        doc.putAll(fields);
        doc.put("_version", 3);
        doc.put("_updatedBy", "guest:Anna");
        doc.put("_updatedAt", "2026-10-22T10:00:00Z");
        return doc;
    }

    private static String error(MigrationSandbox.Outcome outcome) {
        assertThat(outcome).isInstanceOf(MigrationSandbox.Failed.class);
        return ((MigrationSandbox.Failed) outcome).error();
    }
}
