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
            log.info("Migrations applied (chat_threads + clerk auth columns ready)");
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