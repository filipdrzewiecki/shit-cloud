package com.drzewiecki.shitcloud.buildchat;

import com.drzewiecki.shitcloud.data.DocView;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cutting only; reading from the database is covered in {@code BuildChatControllerIT}.
 */
class SampleDocsToolTest {

    private static final Instant AT = Instant.parse("2026-10-21T08:00:00Z");

    private final JsonMapper json = JsonMapper.builder().build();
    private final SampleDocsTool tool = new SampleDocsTool(null, json);

    @Test
    void document_isFlat_asTheSdkReturnsIt() {
        String text = tool.cut(new DocView("anna_2026-10-21", Map.of("type", "office"), 3, "a@x", AT, "b@x", AT));

        assertThat(read(text)).isEqualTo(Map.of("id", "anna_2026-10-21", "type", "office", "_version", 3,
                "_updatedBy", "b@x", "_updatedAt", "2026-10-21T08:00:00Z"));
    }

    @Test
    void longStrings_areShortened_alsoWhenNested() {
        String note = "n".repeat(SampleDocsTool.MAX_STRING_CHARS + 50);
        String text = tool.cut(new DocView("d", Map.of("note", note, "items", List.of(Map.of("text", note))),
                1, "a", AT, "a", AT));

        Map<String, Object> doc = read(text);
        String shortened = "n".repeat(SampleDocsTool.MAX_STRING_CHARS) + "…(250 chars)";
        assertThat(doc.get("note")).isEqualTo(shortened);
        assertThat(doc.get("items")).isEqualTo(List.of(Map.of("text", shortened)));
    }

    @Test
    void bigDocument_isCut_withANote() {
        Map<String, Object> fields = new java.util.LinkedHashMap<>();
        for (int i = 0; i < 100; i++) {
            fields.put("field" + i, "value number " + i);
        }
        String text = tool.cut(new DocView("d", fields, 1, "a", AT, "a", AT));

        assertThat(text).matches("(?s).*…\\(cut, \\d+ bytes\\)$");
        assertThat(text.substring(0, text.indexOf(" …(cut"))).hasSizeLessThanOrEqualTo(SampleDocsTool.MAX_DOC_BYTES);
    }

    private Map<String, Object> read(String text) {
        return json.readValue(text, new TypeReference<>() {
        });
    }
}
