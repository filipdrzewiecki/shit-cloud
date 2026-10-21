package com.drzewiecki.shitcloud.chat;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DataPartsTest {

    @Test
    void answerInProgress_winsOverSavedMessages_andTheNewestPartWins() {
        List<UiMessage> saved = List.of(
                assistant(draft("v1"), Map.of("type", "text", "text", "ok")),
                UiMessage.userText("u2", "change it"),
                assistant(draft("v2")));
        List<Map<String, Object>> answer = new ArrayList<>();
        DataParts parts = DataParts.of(saved, answer);

        assertThat(parts.latest("draft", "draft")).contains(Map.of("html", "v2"));

        // The engine adds to the same list while the answer runs; the lookup sees it at once.
        answer.add(draft("v3"));
        answer.add(draft("v4"));
        assertThat(parts.latest("draft", "draft")).contains(Map.of("html", "v4"));
    }

    @Test
    void nameAndId_mustBothMatch() {
        Map<String, Object> noId = Map.of("type", "data-note", "data", "plain");
        DataParts parts = DataParts.of(List.of(assistant(draft("v1"), noId)), List.of());

        assertThat(parts.latest("draft", "other")).isEmpty();
        assertThat(parts.latest("other", "draft")).isEmpty();
        assertThat(parts.latest("note", null)).contains("plain");
        assertThat(DataParts.NONE.latest("draft", "draft")).isEmpty();
    }

    private static Map<String, Object> draft(String html) {
        return UiParts.data("draft", "draft", Map.of("html", html), false);
    }

    @SafeVarargs
    private static UiMessage assistant(Map<String, Object>... parts) {
        return new UiMessage("m_" + parts.length + parts[0].hashCode(), UiMessage.ASSISTANT, List.of(parts));
    }
}
