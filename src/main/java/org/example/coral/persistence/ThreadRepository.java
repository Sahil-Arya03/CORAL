package org.example.coral.persistence;

import org.example.coral.dto.ChatDtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ThreadRepository {

    private static final Logger log = LoggerFactory.getLogger(ThreadRepository.class);
    static final int MAX_THREADS = 5;

    private final NamedParameterJdbcTemplate jdbc;

    public ThreadRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ChatDtos.ThreadDto> list(long userId) {
        try {
            return jdbc.query("""
                    SELECT t.id, t.title, t.updated_at::text,
                           COUNT(c.id) AS message_count
                    FROM creatoros.chat_threads t
                    LEFT JOIN creatoros.ai_conversations c
                           ON c.session_id = t.id AND c.user_id = t.user_id
                    WHERE t.user_id = :uid
                    GROUP BY t.id, t.title, t.updated_at
                    ORDER BY t.updated_at DESC
                    """,
                    new MapSqlParameterSource("uid", userId),
                    (rs, i) -> new ChatDtos.ThreadDto(
                            UUID.fromString(rs.getString("id")),
                            rs.getString("title"),
                            rs.getString("updated_at"),
                            rs.getInt("message_count")));
        } catch (Exception e) {
            log.warn("thread list failed: {}", e.getMessage());
            return List.of();
        }
    }

    public Optional<ChatDtos.ThreadDto> create(long userId, String title) {
        try {
            if (count(userId) >= MAX_THREADS) return Optional.empty();
            UUID id = UUID.randomUUID();
            String clean = (title == null || title.isBlank()) ? "New Chat" : title;
            jdbc.update("""
                    INSERT INTO creatoros.chat_threads (id, user_id, title)
                    VALUES (:id, :uid, :title)
                    """,
                    new MapSqlParameterSource()
                            .addValue("id", id)
                            .addValue("uid", userId)
                            .addValue("title", clean));
            return Optional.of(new ChatDtos.ThreadDto(id, clean, Instant.now().toString(), 0));
        } catch (Exception e) {
            log.warn("thread create failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public void delete(UUID id, long userId) {
        try {
            jdbc.update("DELETE FROM creatoros.chat_threads WHERE id = :id AND user_id = :uid",
                    new MapSqlParameterSource().addValue("id", id).addValue("uid", userId));
        } catch (Exception e) {
            log.warn("thread delete failed: {}", e.getMessage());
        }
    }

    public void touch(UUID id) {
        try {
            jdbc.update("UPDATE creatoros.chat_threads SET updated_at = now() WHERE id = :id",
                    new MapSqlParameterSource("id", id));
        } catch (Exception e) {
            log.warn("thread touch failed: {}", e.getMessage());
        }
    }

    public void updateTitle(UUID id, long userId, String title) {
        try {
            jdbc.update("""
                    UPDATE creatoros.chat_threads SET title = :title
                    WHERE id = :id AND user_id = :uid
                    """,
                    new MapSqlParameterSource()
                            .addValue("id", id)
                            .addValue("uid", userId)
                            .addValue("title", title));
        } catch (Exception e) {
            log.warn("thread updateTitle failed: {}", e.getMessage());
        }
    }

    public int count(long userId) {
        try {
            Integer n = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM creatoros.chat_threads WHERE user_id = :uid",
                    new MapSqlParameterSource("uid", userId), Integer.class);
            return n == null ? 0 : n;
        } catch (Exception e) {
            return 0;
        }
    }
}