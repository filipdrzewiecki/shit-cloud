package com.drzewiecki.shitcloud.chat;

import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Writes the SSE frames of the AI SDK UI message stream: {@code data: <one-line JSON>}, comment pings, and the
 * closing {@code [DONE]}. Every frame is flushed at once. Writes come from the answering thread and the ping
 * timer, so they are serialised here.
 * <p>
 * A write to a client that has gone (Stop, closed tab) throws {@link ClientGoneException}: that is the only way
 * the server learns about it.
 */
public class UiStreamWriter {

    private final OutputStream out;
    private final JsonMapper json;

    public UiStreamWriter(OutputStream out, JsonMapper json) {
        this.out = out;
        this.json = json;
    }

    public void part(Map<String, Object> part) {
        // '<' only occurs inside JSON strings, so escaping it keeps "</script>" out of anything that inlines
        // the stream without changing what the client parses.
        write("data: " + json.writeValueAsString(part).replace("<", "\\u003c") + "\n\n");
    }

    /**
     * A comment line: keeps proxies from closing a stream that is quiet while the model writes a long tool input.
     */
    public void ping() {
        write(": ping\n\n");
    }

    public void done() {
        write("data: [DONE]\n\n");
    }

    private synchronized void write(String frame) {
        try {
            out.write(frame.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException e) {
            throw new ClientGoneException(e);
        }
    }

    public static class ClientGoneException extends RuntimeException {

        public ClientGoneException(IOException cause) {
            super("The client closed the stream", cause);
        }
    }
}
