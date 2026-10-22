package com.drzewiecki.shitcloud.buildchat;

import com.drzewiecki.shitcloud.chat.ChatTool;
import com.drzewiecki.shitcloud.chat.Conversation;
import com.drzewiecki.shitcloud.chat.ToolResult;
import com.drzewiecki.shitcloud.chat.TurnContext;
import com.drzewiecki.shitcloud.service.AppsProperties;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SaveDraftToolTest {

    private final SaveDraftTool tool = new SaveDraftTool(new Drafts(
            new AppsProperties(Duration.ofDays(90), DataSize.ofBytes(100), false, 8081, "http://apps/{slug}/"),
            new Uploads(null) {
                @Override
                public Optional<Upload> find(String conversationId) {
                    return Optional.empty();
                }
            }));

    private final TurnContext rework = new TurnContext("b@example.com",
            new Conversation("c_1", "b@example.com", "T", "lunch-poll", Instant.now(), Instant.now()));

    @Test
    void wholeDocument_becomesTheDraft_namingTheAppBeingReworked() {
        ToolResult result = tool.run(Map.of("html", "<html>hi</html>"), rework);

        assertThat(result.output()).isEqualTo(Map.of("saved", true, "bytes", 15L));
        assertThat(result.data()).containsExactly(new ToolResult.DataPart("draft", "draft",
                Map.of("html", "<html>hi</html>", "targetSlug", "lunch-poll")));
    }

    @Test
    void missingFragmentOrTooBig_isRefusedWithAReasonForTheModel() {
        assertThatThrownBy(() -> tool.run(Map.of(), rework)).isInstanceOf(ChatTool.ToolException.class)
                .hasMessageContaining("html is missing");
        assertThatThrownBy(() -> tool.run(Map.of("html", "<div>only a part</div>"), rework))
                .hasMessageContaining("whole HTML document");
        assertThatThrownBy(() -> tool.run(Map.of("html", "<html>" + "x".repeat(100) + "</html>"), rework))
                .hasMessageContaining("at most 100");
    }

    @Test
    void history_keepsNoSecondCopyOfTheFile() {
        assertThat(tool.inputForHistory(Map.of("html", "<html>big</html>"))).doesNotContainValue("<html>big</html>");
    }
}
