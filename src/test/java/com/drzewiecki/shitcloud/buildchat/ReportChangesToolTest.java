package com.drzewiecki.shitcloud.buildchat;

import com.drzewiecki.shitcloud.chat.ChatTool;
import com.drzewiecki.shitcloud.chat.Conversation;
import com.drzewiecki.shitcloud.chat.ToolResult;
import com.drzewiecki.shitcloud.chat.TurnContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReportChangesToolTest {

    private final ReportChangesTool tool = new ReportChangesTool();
    private final TurnContext context = new TurnContext("b@example.com",
            new Conversation("c_1", "b@example.com", "Import a.html", null, Instant.now(), Instant.now()));

    @Test
    void theChanges_becomeACard_andTheUserSeesTheirCount() {
        ToolResult result = tool.run(Map.of("changes", List.of(
                Map.of("kind", "replaced", "what", "jQuery by plain JavaScript", "why", "no CDNs"),
                Map.of("kind", "wont_work", "what", "  Weather box  "))), context);

        assertThat(result.output()).isEqualTo(Map.of("changes", 2));
        assertThat(result.data()).singleElement().satisfies(part -> {
            assertThat(part.name()).isEqualTo("changes");
            assertThat(part.id()).isEqualTo("changes");
            assertThat(part.data()).isEqualTo(Map.of("changes", List.of(
                    Map.of("kind", "replaced", "what", "jQuery by plain JavaScript", "why", "no CDNs"),
                    Map.of("kind", "wont_work", "what", "Weather box", "why", ""))));
        });
    }

    @Test
    void longTexts_areCut() {
        ToolResult result = tool.run(Map.of("changes", List.of(Map.of("kind", "moved", "what", "x".repeat(500)))), context);

        @SuppressWarnings("unchecked")
        List<Map<String, String>> changes = (List<Map<String, String>>) ((Map<String, Object>) result.data().getFirst().data()).get("changes");
        assertThat(changes.getFirst().get("what")).hasSize(ReportChangesTool.MAX_CHARS).endsWith("…");
    }

    @Test
    void badInput_isExplainedToTheModel() {
        assertThatThrownBy(() -> tool.run(Map.of(), context))
                .isInstanceOf(ChatTool.ToolException.class).hasMessageStartingWith("changes is missing");
        assertThatThrownBy(() -> tool.run(Map.of("changes", List.of(Map.of("kind", "fixed", "what", "x"))), context))
                .isInstanceOf(ChatTool.ToolException.class).hasMessageStartingWith("change 1: kind must be one of");
        assertThatThrownBy(() -> tool.run(Map.of("changes", List.of(Map.of("kind", "moved", "what", " "))), context))
                .isInstanceOf(ChatTool.ToolException.class).hasMessage("change 1: what is missing");
        assertThatThrownBy(() -> tool.run(Map.of("changes", List.of("text")), context))
                .isInstanceOf(ChatTool.ToolException.class).hasMessage("change 1 is not an object");
        assertThatThrownBy(() -> tool.run(Map.of("changes",
                java.util.Collections.nCopies(51, Map.of("kind", "moved", "what", "x"))), context))
                .isInstanceOf(ChatTool.ToolException.class).hasMessageStartingWith("At most 50 changes");
    }

    @Test
    void anUploadedFileName_losesItsFolders() {
        assertThat(BuildChatController.fileName("C:\\Users\\me\\planner.html")).isEqualTo("planner.html");
        assertThat(BuildChatController.fileName("docs/x/planner.html")).isEqualTo("planner.html");
        assertThat(BuildChatController.fileName(" planner.html ")).isEqualTo("planner.html");
    }
}
