package org.example.coral.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;

/** Read/write creatoros.sync_state — one row per integration key. */
@Repository
public class SyncStateRepository {

    private static final Logger log = LoggerFactory.getLogger(SyncStateRepository.class);

    private final NamedParameterJdbcTemplate jdbc;

    SyncStateRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Instant> getLastSyncedAt(String integration) {
        try {
            var rows = jdbc.queryForList(
                    "SELECT last_synced_at FROM creatoros.sync_state WHERE integration = :k",
                    new MapSqlParameterSource("k", integration));
            if (rows.isEmpty() || rows.get(0).get("last_synced_at") == null) return Optional.empty();
            Object v = rows.get(0).get("last_synced_at");
            return Optional.of(toInstant(v));
        } catch (Exception e) {
            log.warn("sync_state read failed for {}: {}", integration, e.getMessage());
            return Optional.empty();
        }
    }

    private static Instant toInstant(Object v) {
        if (v instanceof Timestamp ts)       return ts.toInstant();
        if (v instanceof OffsetDateTime odt) return odt.toInstant();
        if (v instanceof Instant i)          return i;
        // fallback: ISO-8601 string stored by update()
        return Instant.parse(v.toString());
    }

    void update(String integration, Instant syncedAt) {
        try {
            jdbc.update("""
                    INSERT INTO creatoros.sync_state (integration, last_synced_at, updated_at)
                    VALUES (:k, :ts, now())
                    ON CONFLICT (integration) DO UPDATE
                        SET last_synced_at = EXCLUDED.last_synced_at, updated_at = now()
                    """,
                    new MapSqlParameterSource("k", integration).addValue("ts", syncedAt.toString()));
        } catch (Exception e) {
            log.warn("sync_state write failed for {}: {}", integration, e.getMessage());
        }
    }
}