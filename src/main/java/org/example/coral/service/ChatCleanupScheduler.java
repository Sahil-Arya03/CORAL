package org.example.coral.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes chat threads (and their conversation history, via ON DELETE CASCADE)
 * that have not been active for more than 2 days. Runs daily at 02:00.
 */
@Component
public class ChatCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(ChatCleanupScheduler.class);

    private final NamedParameterJdbcTemplate jdbc;

    public ChatCleanupScheduler(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Scheduled(cron = "0 0 2 * * *")
    public void purgeOldThreads() {
        try {
            int deleted = jdbc.getJdbcOperations().update("""
                    DELETE FROM creatoros.chat_threads
                    WHERE updated_at < now() - INTERVAL '2 days'
                    """);
            if (deleted > 0) {
                log.info("Chat cleanup: removed {} thread(s) inactive for >2 days", deleted);
            }
        } catch (Exception e) {
            log.warn("Chat cleanup failed: {}", e.getMessage());
        }
    }
}
