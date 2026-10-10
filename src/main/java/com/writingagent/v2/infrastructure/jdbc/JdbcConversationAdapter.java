package com.writingagent.v2.infrastructure.jdbc;

import com.writingagent.v2.application.port.ConversationPort;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcConversationAdapter implements ConversationPort {

    private final JdbcTemplate jdbc;

    public JdbcConversationAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insertUserMessage(UUID messageId, UUID conversationId, String body) {
        jdbc.update(
                """
                INSERT INTO writing_v2.conversation_message(message_id, conversation_id, role, body)
                VALUES (?, ?, 'user', ?)
                """,
                messageId,
                conversationId,
                body);
    }

    @Override
    public void insertAssistantMessage(UUID messageId, UUID conversationId, String bodyJson) {
        insertAssistantMessage(messageId, conversationId, bodyJson, null);
    }

    @Override
    public void insertAssistantMessage(UUID messageId, UUID conversationId, String bodyJson, UUID sourceRunId) {
        jdbc.update(
                """
                INSERT INTO writing_v2.conversation_message(message_id, conversation_id, role, body, source_run_id)
                VALUES (?, ?, 'assistant', ?, ?)
                """,
                messageId,
                conversationId,
                bodyJson, sourceRunId);
    }

    @Override
    public List<MessageView> listMessages(UUID conversationId) {
        return jdbc.query(
                """
                SELECT message_id, role, body, created_at, source_run_id
                FROM writing_v2.conversation_message
                WHERE conversation_id = ?
                ORDER BY created_at ASC, message_id ASC
                """,
                (rs, rowNum) -> {
                    Timestamp ts = rs.getTimestamp("created_at");
                    Instant at = ts == null ? Instant.EPOCH : ts.toInstant();
                    return new MessageView(
                            (UUID) rs.getObject("message_id"),
                            rs.getString("role"),
                            rs.getString("body"),
                            at, (UUID) rs.getObject("source_run_id"));
                },
                conversationId);
    }

    @Override
    public boolean belongsToProject(UUID conversationId, UUID projectId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM writing_v2.conversation WHERE conversation_id=? AND project_id=?",
                Integer.class, conversationId, projectId);
        return count != null && count == 1;
    }
}
