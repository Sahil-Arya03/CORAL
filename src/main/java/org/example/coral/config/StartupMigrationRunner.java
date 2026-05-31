package org.example.coral.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Applies schema additions and demo-data that may be missing from the Neon DB
 * (e.g. tables added after initial setup). Every statement is idempotent so this
 * is safe to run on every startup.
 */
@Component
public class StartupMigrationRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupMigrationRunner.class);

    private final NamedParameterJdbcTemplate jdbc;

    public StartupMigrationRunner(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        applyMigrations();
        ensureDemoUser();
    }

    private void applyMigrations() {
        try {
            jdbc.getJdbcOperations().execute("""
                    CREATE TABLE IF NOT EXISTS creatoros.chat_threads (
                        id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
                        user_id    BIGINT      REFERENCES creatoros.users(id) ON DELETE CASCADE,
                        title      TEXT        NOT NULL DEFAULT 'New Chat',
                        created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                        updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
                    )
                    """);
            jdbc.getJdbcOperations().execute("""
                    CREATE INDEX IF NOT EXISTS idx_threads_user
                        ON creatoros.chat_threads (user_id, updated_at DESC)
                    """);
            // Clerk auth columns — idempotent, safe to run every startup
            jdbc.getJdbcOperations().execute(
                    "ALTER TABLE creatoros.users ADD COLUMN IF NOT EXISTS clerk_id TEXT");
            jdbc.getJdbcOperations().execute(
                    "ALTER TABLE creatoros.users ADD COLUMN IF NOT EXISTS username  TEXT");
            jdbc.getJdbcOperations().execute(
                    "CREATE UNIQUE INDEX IF NOT EXISTS idx_users_clerk_id ON creatoros.users (clerk_id) WHERE clerk_id IS NOT NULL");
            // Calendar columns added when the sync adapter was wired up
            jdbc.getJdbcOperations().execute(
                    "ALTER TABLE calendar.events ADD COLUMN IF NOT EXISTS description TEXT");
            jdbc.getJdbcOperations().execute(
                    "ALTER TABLE calendar.events ADD COLUMN IF NOT EXISTS location TEXT");

            // Per-user integration credentials table
            jdbc.getJdbcOperations().execute("""
                    CREATE TABLE IF NOT EXISTS creatoros.user_integrations (
                        id             BIGSERIAL    PRIMARY KEY,
                        user_id        TEXT         NOT NULL,
                        provider       TEXT         NOT NULL,
                        access_token   TEXT,
                        refresh_token  TEXT,
                        token_expiry   TIMESTAMPTZ,
                        extra          JSONB,
                        connected_at   TIMESTAMPTZ  DEFAULT NOW(),
                        last_synced_at TIMESTAMPTZ,
                        UNIQUE (user_id, provider)
                    )
                    """);
            jdbc.getJdbcOperations().execute(
                    "CREATE INDEX IF NOT EXISTS idx_user_integrations_user ON creatoros.user_integrations (user_id)");
            jdbc.getJdbcOperations().execute(
                    "CREATE INDEX IF NOT EXISTS idx_user_integrations_provider ON creatoros.user_integrations (provider)");

            // user_id scoping column on all federated tables (Clerk user ID, TEXT)
            for (String tbl : new String[]{
                    "github.commits", "github.pull_requests",
                    "notion.tasks", "calendar.events", "gmail.emails"}) {
                jdbc.getJdbcOperations().execute(
                        "ALTER TABLE " + tbl + " ADD COLUMN IF NOT EXISTS user_id TEXT NOT NULL DEFAULT ''");
            }
            jdbc.getJdbcOperations().execute(
                    "CREATE INDEX IF NOT EXISTS idx_commits_user     ON github.commits (user_id)");
            jdbc.getJdbcOperations().execute(
                    "CREATE INDEX IF NOT EXISTS idx_prs_user         ON github.pull_requests (user_id)");
            jdbc.getJdbcOperations().execute(
                    "CREATE INDEX IF NOT EXISTS idx_tasks_user       ON notion.tasks (user_id)");
            jdbc.getJdbcOperations().execute(
                    "CREATE INDEX IF NOT EXISTS idx_events_user      ON calendar.events (user_id)");
            jdbc.getJdbcOperations().execute(
                    "CREATE INDEX IF NOT EXISTS idx_emails_user      ON gmail.emails (user_id)");

            // google_event_id — original Google Calendar event ID, needed for Calendar write-back
            jdbc.getJdbcOperations().execute(
                    "ALTER TABLE calendar.events ADD COLUMN IF NOT EXISTS google_event_id TEXT");

            // Purge seed / demo rows that have no real user_id so they don't pollute real data
            jdbc.getJdbcOperations().execute(
                    "DELETE FROM gmail.emails     WHERE user_id = ''");
            jdbc.getJdbcOperations().execute(
                    "DELETE FROM calendar.events  WHERE user_id = ''");

            log.info("Migrations applied — user_integrations + federated user_id + seed purge done");
        } catch (Exception e) {
            log.error("Migration failed: {}", e.getMessage());
        }
    }

    private void ensureDemoUser() {
        try {
            jdbc.update("""
                    INSERT INTO creatoros.users (id, email, display_name)
                    VALUES (1, 'arya@creatoros.dev', 'Alex')
                    ON CONFLICT (id) DO NOTHING
                    """,
                    new MapSqlParameterSource());
            log.info("Demo user ready (id=1)");
        } catch (Exception e) {
            log.warn("ensureDemoUser failed (may already exist): {}", e.getMessage());
        }
    }
}