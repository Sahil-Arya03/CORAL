package org.example.coral.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * CRUD for creatoros.user_integrations.
 * user_id is the Clerk user ID (TEXT), not the internal BIGINT.
 */
@Repository
public class UserIntegrationRepository {

    private static final Logger log = LoggerFactory.getLogger(UserIntegrationRepository.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final NamedParameterJdbcTemplate jdbc;

    public UserIntegrationRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<UserIntegration> findByUserId(String userId) {
        try {
            return jdbc.query("""
                    SELECT id, user_id, provider, access_token, refresh_token,
                           token_expiry::text, extra::text, connected_at::text, last_synced_at::text
                    FROM creatoros.user_integrations
                    WHERE user_id = :uid
                    ORDER BY connected_at DESC
                    """,
                    new MapSqlParameterSource("uid", userId),
                    (rs, i) -> map(rs));
        } catch (Exception e) {
            log.warn("findByUserId failed: {}", e.getMessage());
            return List.of();
        }
    }

    public Optional<UserIntegration> findByUserAndProvider(String userId, String provider) {
        try {
            var rows = jdbc.query("""
                    SELECT id, user_id, provider, access_token, refresh_token,
                           token_expiry::text, extra::text, connected_at::text, last_synced_at::text
                    FROM creatoros.user_integrations
                    WHERE user_id = :uid AND provider = :prov
                    """,
                    new MapSqlParameterSource("uid", userId).addValue("prov", provider),
                    (rs, i) -> map(rs));
            return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
        } catch (Exception e) {
            log.warn("findByUserAndProvider failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** Returns all Clerk user IDs that have the given provider connected. */
    public List<String> findUsersByProvider(String provider) {
        try {
            return jdbc.queryForList(
                    "SELECT user_id FROM creatoros.user_integrations WHERE provider = :prov",
                    new MapSqlParameterSource("prov", provider),
                    String.class);
        } catch (Exception e) {
            log.warn("findUsersByProvider failed: {}", e.getMessage());
            return List.of();
        }
    }

    public void upsert(String userId, String provider, String accessToken,
                       String refreshToken, Map<String, Object> extra) {
        try {
            String extraJson = extra == null ? null : JSON.writeValueAsString(extra);
            jdbc.update("""
                    INSERT INTO creatoros.user_integrations
                        (user_id, provider, access_token, refresh_token, extra, connected_at)
                    VALUES (:uid, :prov, :at, :rt, CAST(:extra AS jsonb), now())
                    ON CONFLICT (user_id, provider) DO UPDATE
                        SET access_token  = EXCLUDED.access_token,
                            refresh_token = EXCLUDED.refresh_token,
                            extra         = EXCLUDED.extra,
                            connected_at  = now()
                    """,
                    new MapSqlParameterSource()
                            .addValue("uid",   userId)
                            .addValue("prov",  provider)
                            .addValue("at",    accessToken)
                            .addValue("rt",    refreshToken)
                            .addValue("extra", extraJson));
        } catch (Exception e) {
            log.error("upsert failed for {}/{}: {}", userId, provider, e.getMessage());
        }
    }

    public void updateLastSynced(String userId, String provider) {
        try {
            jdbc.update("""
                    UPDATE creatoros.user_integrations
                    SET last_synced_at = now()
                    WHERE user_id = :uid AND provider = :prov
                    """,
                    new MapSqlParameterSource("uid", userId).addValue("prov", provider));
        } catch (Exception e) {
            log.warn("updateLastSynced failed: {}", e.getMessage());
        }
    }

    public void deleteByUserAndProvider(String userId, String provider) {
        try {
            jdbc.update(
                    "DELETE FROM creatoros.user_integrations WHERE user_id = :uid AND provider = :prov",
                    new MapSqlParameterSource("uid", userId).addValue("prov", provider));
        } catch (Exception e) {
            log.warn("delete integration failed: {}", e.getMessage());
        }
    }

    // ── Mapper ────────────────────────────────────────────────────────────────

    private static UserIntegration map(java.sql.ResultSet rs) throws java.sql.SQLException {
        String extraRaw = rs.getString("extra");
        Map<String, Object> extra = Map.of();
        if (extraRaw != null && !extraRaw.isBlank()) {
            try {
                extra = JSON.readValue(extraRaw, new TypeReference<>() {});
            } catch (JsonProcessingException ignored) {}
        }
        return new UserIntegration(
                rs.getLong("id"),
                rs.getString("user_id"),
                rs.getString("provider"),
                rs.getString("access_token"),
                rs.getString("refresh_token"),
                parseInstant(rs.getString("token_expiry")),
                extra,
                parseInstant(rs.getString("connected_at")),
                parseInstant(rs.getString("last_synced_at")));
    }

    private static Instant parseInstant(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Instant.parse(s); } catch (Exception e) { return null; }
    }

    // ── Domain record ─────────────────────────────────────────────────────────

    public record UserIntegration(
            long id,
            String userId,
            String provider,
            String accessToken,
            String refreshToken,
            Instant tokenExpiry,
            Map<String, Object> extra,
            Instant connectedAt,
            Instant lastSyncedAt
    ) {
        /** Read a string list from the JSONB extra map (e.g. repos list for GitHub). */
        @SuppressWarnings("unchecked")
        public List<String> extraAsList(String key) {
            Object val = extra.get(key);
            if (val instanceof List<?> list) {
                return (List<String>) list;
            }
            return List.of();
        }

        public String extraString(String key) {
            Object val = extra.get(key);
            return val instanceof String s ? s : null;
        }
    }
}