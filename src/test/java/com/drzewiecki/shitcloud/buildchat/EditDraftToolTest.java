package com.drzewiecki.shitcloud.buildchat;

import com.drzewiecki.shitcloud.chat.ChatTool;
import com.drzewiecki.shitcloud.chat.Conversation;
import com.drzewiecki.shitcloud.chat.DataParts;
import com.drzewiecki.shitcloud.chat.ToolResult;
import com.drzewiecki.shitcloud.chat.TurnContext;
import com.drzewiecki.shitcloud.service.AppsProperties;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The deployed-app base needs the registry and is covered in {@code BuildChatControllerIT}; here the registry is
 * never reached.
 */
class EditDraftToolTest {

    private static final String HTML = "<html><body><h1>Poll</h1><li>a</li><li>b</li></body></html>";

    private final EditDraftTool tool = new EditDraftTool(new Drafts(
            new AppsProperties(Duration.ofDays(90), DataSize.ofBytes(200), false, 8081, "http://apps/{slug}/")),
            null);

    @Test
    void edits_applyInOrder_toTheLatestDraft_andBecomeTheNextDraft() {
        ToolResult result = tool.run(edits(
                edit("<h1>Poll</h1>", "<h1>Lunch poll</h1>"),
                edit("Lunch poll", "Lunch poll v2")), withDraft(HTML, "lunch-poll"));

        String expected = HTML.replace("<h1>Poll</h1>", "<h1>Lunch poll v2</h1>");
        assertThat(result.output()).isEqualTo(Map.of("edited", 2, "bytes", (long) expected.length()));
        assertThat(result.forModel()).startsWith("Applied 2 edit(s)").doesNotContain("<html>");
        assertThat(result.data()).containsExactly(new ToolResult.DataPart("draft", "draft",
                Map.of("html", expected, "targetSlug", "lunch-poll")));
    }

    @Test
    void emptyNewText_removesTheSnippet() {
        assertThat(EditDraftTool.apply(HTML, edit("<li>b</li>", ""), 1))
                .isEqualTo("<html><body><h1>Poll</h1><li>a</li></body></html>");
    }

    @Test
    void missingOrRepeatedSnippet_isRefused_namingTheEdit() {
        assertThatThrownBy(() -> EditDraftTool.apply(HTML, edit("<li>c</li>", "x"), 3))
                .isInstanceOf(ChatTool.ToolException.class).hasMessageStartingWith("edit 3: old_text not found");
        assertThatThrownBy(() -> EditDraftTool.apply(HTML, edit("<li>", "<li class=x>"), 2))
                .hasMessageStartingWith("edit 2: old_text found 2 times");
        // Overlapping occurrences are ambiguous too.
        assertThatThrownBy(() -> EditDraftTool.apply("<html>aaa</html>", edit("aa", "b"), 1))
                .hasMessageContaining("found 2 times");
    }

    @Test
    void emptyOrUnchangedOrMalformedEdit_isRefused() {
        assertThatThrownBy(() -> EditDraftTool.apply(HTML, edit("", "x"), 1)).hasMessageContaining("old_text is empty");
        assertThatThrownBy(() -> EditDraftTool.apply(HTML, edit("<h1>", "<h1>"), 1)).hasMessageContaining("the same");
        assertThatThrownBy(() -> EditDraftTool.apply(HTML, Map.of("old_text", "<h1>"), 1))
                .hasMessageContaining("needs old_text and new_text");
        assertThatThrownBy(() -> EditDraftTool.apply(HTML, "replace everything", 1))
                .hasMessageContaining("needs old_text and new_text");
    }

    @Test
    void oneFailingEdit_appliesNothing() {
        TurnContext context = withDraft(HTML, null);

        assertThatThrownBy(() -> tool.run(edits(edit("<h1>Poll</h1>", "<h1>New</h1>"), edit("missing", "x")), context))
                .hasMessageContaining("edit 2");
        // The draft is unchanged: the first edit still applies to the original file.
        assertThat(tool.run(edits(edit("<h1>Poll</h1>", "<h1>New</h1>")), context).output())
                .isEqualTo(Map.of("edited", 1, "bytes", (long) HTML.replace("Poll", "New").length()));
    }

    @Test
    void noDraftInANewAppConversation_pointsToSaveDraft() {
        TurnContext context = new TurnContext("b@example.com", conversation(null));

        assertThatThrownBy(() -> tool.run(edits(edit("a", "b")), context))
                .hasMessageContaining("create the app with save_draft first");
    }

    @Test
    void noEdits_tooMany_orAResultThatIsNoLongerAnApp_isRefused() {
        TurnContext context = withDraft(HTML, null);

        assertThatThrownBy(() -> tool.run(Map.of(), context)).hasMessageContaining("edits is missing");
        assertThatThrownBy(() -> tool.run(Map.of("edits", List.of()), context)).hasMessageContaining("edits is missing");
        List<Map<String, Object>> many = java.util.stream.IntStream.rangeClosed(0, EditDraftTool.MAX_EDITS)
                .mapToObj(i -> edit("x" + i, "y")).toList();
        assertThatThrownBy(() -> tool.run(Map.of("edits", many), context)).hasMessageContaining("At most 50 edits");
        assertThatThrownBy(() -> tool.run(edits(edit("<html><body>", "<body>")), context))
                .hasMessageContaining("whole HTML document");
        assertThatThrownBy(() -> tool.run(edits(edit("<li>a</li>", "<li>" + "a".repeat(200) + "</li>")), context))
                .hasMessageContaining("an app may have at most 200");
    }

    private static Map<String, Object> edits(Map<?, ?>... edits) {
        return Map.of("edits", List.of(edits));
    }

    private static Map<String, Object> edit(String oldText, String newText) {
        return Map.of("old_text", oldText, "new_text", newText);
    }

    private static TurnContext withDraft(String html, String targetSlug) {
        DataParts drafts = (name, id) -> "draft".equals(name) && "draft".equals(id)
                ? Optional.of(Map.of("html", html)) : Optional.empty();
        return new TurnContext("b@example.com", conversation(targetSlug), drafts);
    }

    private static Conversation conversation(String targetSlug) {
        return new Conversation("c_1", "b@example.com", "T", targetSlug, Instant.now(), Instant.now());
    }
}
