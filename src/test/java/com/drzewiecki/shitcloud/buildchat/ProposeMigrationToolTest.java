package com.drzewiecki.shitcloud.buildchat;

import com.drzewiecki.shitcloud.chat.ChatTool;
import com.drzewiecki.shitcloud.chat.Conversation;
import com.drzewiecki.shitcloud.chat.TurnContext;
import com.drzewiecki.shitcloud.migration.MigrationCheck;
import com.drzewiecki.shitcloud.migration.MigrationCounts;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the model reads about a check; running it is covered in {@code MigrationServiceIT} and {@code BuildChatControllerIT}.
 */
class ProposeMigrationToolTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void theModelReadsCountsFieldChangesExamplesAndReaders() {
        MigrationCheck check = check(new MigrationCounts(3, 2, 0, 1, 0), List.of());

        String text = ProposeMigrationTool.describe(check,
                List.of(new ProposeMigrationTool.Reader("office-report", "Office Report", new TreeSet<>(List.of("days")))), json);

        assertThat(text).startsWith("Checked migration 7 of team-calendar — nothing is written yet.\n"
                + "Documents: 3 — 2 changed, 0 unchanged, 1 removed, 0 failed\n");
        assertThat(text).contains("Field changes:\n- days: kind added to 2; type removed from 2\n");
        assertThat(text).contains("- days/d1: {\"type\":\"off\"} → {\"kind\":\"absence\"}");
        assertThat(text).contains("- days/d2: {\"type\":\"office\"} → removed");
        assertThat(text).contains("Other apps read this data and will likely break: office-report (reads days)");
        assertThat(text).endsWith("say clearly that 1 document(s) will be removed.");
    }

    @Test
    void withFailures_theModelIsToldToFixThem() {
        MigrationCheck check = check(new MigrationCounts(3, 2, 0, 0, 1),
                List.of(new MigrationCheck.Failure("days", "d3", "TypeError: boom (line 2)")));

        String text = ProposeMigrationTool.describe(check, List.of(), json);

        assertThat(text).contains("Failures (first 1 of 1):\n- days/d3: TypeError: boom (line 2)\n");
        assertThat(text).doesNotContain("Other apps");
        assertThat(text).endsWith("fix the code and call propose_migration again.");
    }

    @Test
    void aNewAppHasNoDataToConvert() {
        ProposeMigrationTool tool = new ProposeMigrationTool(null, null, json);
        Conversation newApp = new Conversation("c_1", "builder@example.com", "New", null, Instant.now(), Instant.now());

        assertThatThrownBy(() -> tool.run(Map.of("code", "function migrate(d) { return d; }", "summary", "x"),
                new TurnContext("builder@example.com", newApp)))
                .isInstanceOf(ChatTool.ToolException.class)
                .hasMessageStartingWith("propose_migration converts the data of an existing app");
    }

    private static MigrationCheck check(MigrationCounts counts, List<MigrationCheck.Failure> failures) {
        Map<String, MigrationCheck.FieldChange> fields = new LinkedHashMap<>();
        fields.put("kind", new MigrationCheck.FieldChange(2, 0, 0));
        fields.put("type", new MigrationCheck.FieldChange(0, 2, 0));
        return new MigrationCheck(7, "team-calendar", List.of("days"), "type becomes kind", counts,
                Map.of("days", counts), Map.of("days", fields),
                List.of(new MigrationCheck.Example("days", "d1", Map.of("type", "off"), Map.of("kind", "absence")),
                        new MigrationCheck.Example("days", "d2", Map.of("type", "office"), null)),
                failures);
    }
}
