package org.example.coral.persistence;

import org.example.coral.dto.ChatDtos;
import org.example.coral.util.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/** Persists chat turns to creatoros.ai_conversations. Best-effort: a logging failure
    must never break the user-facing request. */
@Repository
public class ConversationRepository {

    private static final Logger log = LoggerFactory.getLogger(ConversationRepository.class);

    private final NamedParameterJdbcTemplate jdbc;

    public ConversationRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Fetch the last {@code limit} turns for a session, ordered oldest-first. */
    public List<java.util.Map<String, Object>> getRecentHistory(long userId, UUID sessionId, int limit) {
        try {
            return jdbc.queryForList("""
                    SELECT role, content FROM (
                        SELECT role, content, created_at
                        FROM creatoros.ai_conversations
                        WHERE user_id = :uid AND session_id = :sid
                        ORDER BY created_at DESC
                        LIMIT :lim
                    ) sub ORDER BY created_at ASC
                    """,
                    new MapSqlParameterSource()
                            .addValue("uid", userId)
                            .addValue("sid", sessionId)
                            .addValue("lim", limit));
        } catch (Exception e) {
            log.warn("conversation history read failed: {}", e.getMessage());
            return List.of();
        }
    }

    /** Load all turns for a thread, oldest-first (used for history display when switching threads). */
    public List<ChatDtos.MessageDto> loadHistory(long userId, UUID sessionId) {
        try {
            return jdbc.query("""
                    SELECT role, content, created_at::text AS ts
                    FROM creatoros.ai_conversations
                    WHERE user_id = :uid AND session_id = :sid
                    ORDER BY created_at ASC
                    """,
                    new MapSqlParameterSource().addValue("uid", userId).addValue("sid", sessionId),
                    (rs, i) -> new ChatDtos.MessageDto(
                            rs.getString("role"),
                            rs.getString("content"),
                            rs.getString("ts")));
        } catch (Exception e) {
            log.warn("load history failed: {}", e.getMessage());
            return List.of();
        }
    }

    public void deleteBySession(long userId, UUID sessionId) {
        try {
            jdbc.update("""
                    DELETE FROM creatoros.ai_conversations
                    WHERE user_id = :uid AND session_id = :sid
                    """,
                    new MapSqlParameterSource().addValue("uid", userId).addValue("sid", sessionId));
        } catch (Exception e) {
            log.warn("deleteBySession failed: {}", e.getMessage());
        }
    }

    public void record(long userId, UUID sessionId, String role, String content,
                       Object intent, Integer tokenUsage) {
        try {
            MapSqlParameterSource p = new MapSqlParameterSource()
                    .addValue("uid", userId)
                    .addValue("sid", sessionId)
                    .addValue("role", role)
                    .addValue("content", content)
                    .addValue("intent", intent == null ? null : Json.write(intent))
                    .addValue("tok", tokenUsage);
            jdbc.update("""
                    INSERT INTO creatoros.ai_conversations
                        (user_id, session_id, role, content, intent, token_usage)
                    VALUES (:uid, :sid, :role, :content, CAST(:intent AS jsonb), :tok)
                    """, p);
        } catch (Exception e) {
            log.warn("ai_conversations write failed: {}", e.getMessage());
        }
    }
}
