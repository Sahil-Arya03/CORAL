package org.example.coral.persistence;

import org.example.coral.util.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Map;

/** Persists mutation audit rows to creatoros.action_logs. Best-effort: never throws
    into the request path. */
@Repository
public class ActionLogRepository {

    private static final Logger log = LoggerFactory.getLogger(ActionLogRepository.class);

    private final NamedParameterJdbcTemplate jdbc;

    public ActionLogRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(long userId, String actionType, String targetSchema, String validatedSql,
                       Map<String, Object> bindings, Object beforeSnapshot, Integer rowsAffected,
                       String policyVerdict, String status) {
        try {
            MapSqlParameterSource p = new MapSqlParameterSource()
                    .addValue("uid", userId)
                    .addValue("atype", actionType)
                    .addValue("tschema", targetSchema)
                    .addValue("sql", validatedSql)
                    .addValue("bindings", bindings == null ? null : Json.write(bindings))
                    .addValue("before", beforeSnapshot == null ? null : Json.write(beforeSnapshot))
                    .addValue("rows", rowsAffected)
                    .addValue("verdict", policyVerdict)
                    .addValue("status", status);
            jdbc.update("""
                    INSERT INTO creatoros.action_logs
                        (user_id, action_type, target_schema, validated_sql, bindings,
                         before_snapshot, rows_affected, policy_verdict, status)
                    VALUES (:uid, :atype, :tschema, :sql, CAST(:bindings AS jsonb),
                            CAST(:before AS jsonb), :rows, :verdict, :status)
                    """, p);
        } catch (Exception e) {
            log.warn("action_logs write failed: {}", e.getMessage());
        }
    }
}
