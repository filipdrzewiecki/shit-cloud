package com.drzewiecki.shitcloud.chat;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Conversations and their messages ({@code chat_conversations}, {@code chat_messages}). Every read is scoped to
 * the owner, so one builder never sees another's conversation.
 */
@Repository
public class ConversationStore {

    private static final TypeReference<List<Map<String, Object>>> PARTS = new TypeReference<>() {
    };

    private static final RowMapper<Conversation> CONVERSATION = (row, i) -> new Conversation(
            row.getString("id"),
            row.getString("owner"),
            row.getString("title"),
            row.getString("target_slug"),
            instant(row, "created_at"),
            instant(row, "updated_at"));

    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;
    private final JsonMapper json;

    public ConversationStore(JdbcClient jdbc, PlatformTransactionManager transactionManager, JsonMapper json) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
        this.json = json;
    }

    public Conversation create(String owner, String title, String targetSlug) {
        Instant now = Instant.now();
        Conversation conversation = new Conversation("c_" + UUID.randomUUID().toString().replace("-", ""), owner,
                title, targetSlug, now, now);
        jdbc.sql("""
                        insert into chat_conversations (id, owner, title, target_slug, created_at, updated_at)
                        values (?, ?, ?, ?, ?, ?)""")
                .params(conversation.id(), owner, title, targetSlug, utc(now), utc(now))
                .update();
        return conversation;
    }

    public Optional<Conversation> find(String id, String owner) {
        return jdbc.sql("select * from chat_conversations where id = ? and owner = ?")
                .params(id, owner)
                .query(CONVERSATION)
                .optional();
    }

    /**
     * Most recently continued first.
     */
    public List<Conversation> list(String owner) {
        return jdbc.sql("select * from chat_conversations where owner = ? order by updated_at desc")
                .param(owner)
                .query(CONVERSATION)
                .list();
    }

    /**
     * In order of writing.
     */
    public List<UiMessage> messages(String conversationId) {
        return jdbc.sql("select id, role, parts from chat_messages where conversation_id = ? order by seq")
                .param(conversationId)
                .query((row, i) -> new UiMessage(row.getString("id"), row.getString("role"),
                        json.readValue(row.getString("parts"), PARTS)))
                .list();
    }

    /**
     * Appends at the end. Locking the conversation's row keeps the sequence gap-free when two answers finish
     * at the same time. {@code usage} is null for the builder's own messages.
     */
    public void append(String conversationId, UiMessage message, Usage usage) {
        Instant now = Instant.now();
        transaction.executeWithoutResult(status -> {
            jdbc.sql("select id from chat_conversations where id = ? for update").param(conversationId)
                    .query(String.class).single();
            int seq = jdbc.sql("select coalesce(max(seq), 0) + 1 from chat_messages where conversation_id = ?")
                    .param(conversationId).query(Integer.class).single();
            jdbc.sql("""
                            insert into chat_messages (conversation_id, seq, id, role, parts, model, input_tokens,
                                                       output_tokens, created_at)
                            values (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)""")
                    .params(conversationId, seq, message.id(), message.role(), json.writeValueAsString(message.parts()),
                            usage == null ? null : usage.model(),
                            usage == null ? null : usage.inputTokens(),
                            usage == null ? null : usage.outputTokens(),
                            utc(now))
                    .update();
            jdbc.sql("update chat_conversations set updated_at = ? where id = ?").params(utc(now), conversationId)
                    .update();
        });
    }

    /**
     * What one answer cost; tokens are summed over its steps.
     */
    public record Usage(String model, int inputTokens, int outputTokens) {
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        return row.getObject(column, OffsetDateTime.class).toInstant();
    }
}
