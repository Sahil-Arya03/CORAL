package org.example.coral.sync;

import org.example.coral.persistence.UserIntegrationRepository;
import org.example.coral.sync.NotionApiClient.NotionTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Orchestrates one Notion sync cycle:
 *   1. Read last_synced_at from creatoros.sync_state (default: 30 days ago)
 *   2. Query Notion database for pages edited since that timestamp
 *   3. Batch-upsert into notion.tasks
 *   4. Write new last_synced_at to sync_state
 *
 * Note: mutations written by the AI (via ActionExecutionService) land in the
 * local DB only. Write-back to Notion is a future enhancement.
 */
@Component
public class NotionSyncAdapter {

    private static final Logger log = LoggerFactory.getLogger(NotionSyncAdapter.class);
    private static final String STATE_KEY = "notion:tasks";

    private final NotionApiClient api;
    private final SyncStateRepository state;
    private final NamedParameterJdbcTemplate jdbc;
    private final UserIntegrationRepository integrationRepo;

    NotionSyncAdapter(NotionApiClient api, SyncStateRepository state,
                      NamedParameterJdbcTemplate jdbc,
                      UserIntegrationRepository integrationRepo) {
        this.api             = api;
        this.state           = state;
        this.jdbc            = jdbc;
        this.integrationRepo = integrationRepo;
    }

    /** Per-user sync using credentials stored in user_integrations. */
    public SyncResult syncForUser(String clerkUserId) {
        var integration = integrationRepo.findByUserAndProvider(clerkUserId, "notion");
        if (integration.isEmpty()) {
            log.debug("Notion sync skipped for {} — no notion integration", clerkUserId);
            return new SyncResult(0);
        }
        String token = integration.get().accessToken();
        String dbId  = integration.get().extraString("database_id");
        if (token == null || token.isBlank() || dbId == null || dbId.isBlank()) {
            log.warn("Notion sync skipped for {} — missing token or database_id", clerkUserId);
            return new SyncResult(0);
        }

        String stateKey = "notion:" + clerkUserId;
        Instant since   = state.getLastSyncedAt(stateKey)
                .orElse(Instant.now().minus(30, ChronoUnit.DAYS));
        Instant syncStart = Instant.now();

        List<NotionTask> tasks = api.queryDatabaseWithCredentials(token, dbId, since);
        if (!tasks.isEmpty()) upsertForUser(tasks, clerkUserId);

        state.update(stateKey, syncStart);
        integrationRepo.updateLastSynced(clerkUserId, "notion");
        log.info("Notion sync for user {} — {} tasks", clerkUserId, tasks.size());
        return new SyncResult(tasks.size());
    }

    /** Global sync using shared NotionProperties credentials (legacy / scheduled). */
    public SyncResult sync() {
        Instant since = state.getLastSyncedAt(STATE_KEY)
                .orElse(Instant.now().minus(30, ChronoUnit.DAYS));
        Instant syncStart = Instant.now();

        log.info("Notion sync start since {}", since);

        List<NotionTask> tasks = api.queryDatabase(since);
        if (tasks.isEmpty()) {
            log.info("Notion sync done — no updated tasks");
            state.update(STATE_KEY, syncStart);
            return new SyncResult(0);
        }

        upsert(tasks);
        state.update(STATE_KEY, syncStart);
        log.info("Notion sync done — {} tasks upserted", tasks.size());
        return new SyncResult(tasks.size());
    }

    private void upsert(List<NotionTask> tasks) {
        upsertForUser(tasks, "");
    }

    private void upsertForUser(List<NotionTask> tasks, String userId) {
        SqlParameterSource[] batch = tasks.stream().map(t -> new MapSqlParameterSource()
                .addValue("id",        t.id() + ":" + userId)
                .addValue("userId",    userId)
                .addValue("title",     t.title())
                .addValue("status",    normalizeStatus(t.status()))
                .addValue("priority",  normalizePriority(t.priority()))
                .addValue("dueDate",   t.dueDate())
                .addValue("project",   t.project())
                .addValue("updatedAt", t.updatedAt() != null ? t.updatedAt().toString() : null))
                .toArray(SqlParameterSource[]::new);

        jdbc.batchUpdate("""
                INSERT INTO notion.tasks (id, user_id, title, status, priority, due_date, project, updated_at)
                VALUES (:id, :userId, :title, :status, :priority,
                        CAST(:dueDate AS timestamptz), :project,
                        CAST(:updatedAt AS timestamptz))
                ON CONFLICT (id) DO UPDATE
                    SET title      = EXCLUDED.title,
                        status     = EXCLUDED.status,
                        priority   = EXCLUDED.priority,
                        due_date   = EXCLUDED.due_date,
                        project    = EXCLUDED.project,
                        updated_at = EXCLUDED.updated_at,
                        user_id    = EXCLUDED.user_id
                """, batch);
    }

    // Normalise Notion's freeform values to the enum the DB expects
    private static String normalizeStatus(String raw) {
        if (raw == null) return "todo";
        return switch (raw.toLowerCase().replace(" ", "_").replace("-", "_")) {
            case "done", "complete", "completed", "finished" -> "done";
            case "in_progress", "doing", "active", "wip"     -> "in_progress";
            default                                           -> "todo";
        };
    }

    private static String normalizePriority(String raw) {
        if (raw == null) return "medium";
        return switch (raw.toLowerCase()) {
            case "high", "urgent", "critical" -> "high";
            case "low", "minor"               -> "low";
            default                           -> "medium";
        };
    }

    public record SyncResult(int tasksUpserted) {}
}