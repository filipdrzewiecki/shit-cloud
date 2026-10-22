package com.drzewiecki.shitcloud.buildchat;

import com.drzewiecki.shitcloud.db.Timestamps;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The file an import conversation started from ({@code chat_uploads}): the original HTML as the builder uploaded it.
 */
@Component
public class Uploads {

    private final JdbcClient jdbc;

    public Uploads(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Upload(String fileName, String html) {
    }

    void save(String conversationId, String fileName, String html) {
        jdbc.sql("insert into chat_uploads (conversation_id, file_name, html, created_at) values (?, ?, ?, ?)")
                .params(conversationId, fileName, html, Timestamps.of(Instant.now()))
                .update();
    }

    public Optional<Upload> find(String conversationId) {
        return jdbc.sql("select file_name, html from chat_uploads where conversation_id = ?")
                .param(conversationId)
                .query((row, i) -> new Upload(row.getString("file_name"), row.getString("html")))
                .optional();
    }

    /** The file names of the given conversations' uploads, for the conversation list. */
    Map<String, String> fileNames(Collection<String> conversationIds) {
        Map<String, String> names = new HashMap<>();
        if (conversationIds.isEmpty()) {
            return names;
        }
        jdbc.sql("select conversation_id, file_name from chat_uploads where conversation_id = any(?)")
                .param(conversationIds.toArray(String[]::new))
                .query((row, i) -> names.put(row.getString("conversation_id"), row.getString("file_name")))
                .list();
        return names;
    }
}
