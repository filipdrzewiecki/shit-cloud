package com.drzewiecki.shitcloud.chat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UiStreamWriterTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void frames_areOneLineDataEvents_pingsAreComments_andTheEndIsDone() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        UiStreamWriter writer = new UiStreamWriter(out, json);

        writer.part(UiParts.start("m_1"));
        writer.part(UiParts.textDelta("t_1", "line one\nzażółć"));
        writer.ping();
        writer.part(UiParts.finish());
        writer.done();

        assertThat(out.toString(StandardCharsets.UTF_8)).isEqualTo("""
                data: {"type":"start","messageId":"m_1"}

                data: {"type":"text-delta","id":"t_1","delta":"line one\\nzażółć"}

                : ping

                data: {"type":"finish"}

                data: [DONE]

                """);
    }

    @Test
    void lessThan_isEscaped_soNoScriptTagEndsUpInTheStream() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        new UiStreamWriter(out, json).part(UiParts.data("draft", "draft", "</script><b>", false));

        String frame = out.toString(StandardCharsets.UTF_8);
        assertThat(frame).doesNotContain("<").contains("\\u003c/script>\\u003cb>");
        assertThat(json.readTree(frame.substring("data: ".length())).get("data").asString()).isEqualTo("</script><b>");
    }

    @Test
    void aClientThatLeft_isReportedAsGone() {
        OutputStream closed = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw new IOException("Broken pipe");
            }
        };

        assertThatThrownBy(() -> new UiStreamWriter(closed, json).ping())
                .isInstanceOf(UiStreamWriter.ClientGoneException.class);
    }
}
