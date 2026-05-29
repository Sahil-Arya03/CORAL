package org.example.coral.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/** Persists planned/validated queries to creatoros.query_logs (prompt-tuning + audit).
    Best-effort: never throws into the request path. */
@Repository
public class QueryLogRepository {

    private static final Logger log = LoggerFactory.getLogger(QueryLogRepository.class);

    private final NamedParameterJdbcTemplate jdbc;

    public QueryLogRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(long userId, UUID sessionId, String userPrompt, String plannedSql,
                       String validationResult, String rejectionReason, Integer latencyMs) {
        try {
            MapSqlParameterSource p = new MapSqlParameterSource()
                    .addValue("uid", userId)
                    .addValue("sid", sessionId)
                    .addValue("prompt", userPrompt)
                    .addValue("sql", plannedSql)
                    .addValue("vres", validationResult)
                    .addValue("rej", rejectionReason)
                    .addValue("lat", latencyMs);
            jdbc.update("""
                    INSERT INTO creatoros.query_logs
                        (user_id, session_id, user_prompt, planned_sql,
                         validation_result, rejection_reason, coral_latency_ms)
                    VALUES (:uid, :sid, :prompt, :sql, :vres, :rej, :lat)
                    """, p);
        } catch (Exception e) {
            log.warn("query_logs write failed: {}", e.getMessage());
        }
    }
}
