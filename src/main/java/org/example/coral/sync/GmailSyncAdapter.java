package org.example.coral.sync;

import org.example.coral.persistence.UserIntegrationRepository;
import org.example.coral.sync.GmailApiClient.GmailMessage;
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
 * Orchestrates one Gmail sync cycle:
 *   1. Read last_synced_at from creatoros.sync_state (default: 30 days ago)
 *   2. Fetch messages from Gmail API filtered by received date
 *   3. Batch-upsert into gmail.emails
 *   4. Write new last_synced_at to sync_state
 *
 * Uses ON CONFLICT DO UPDATE so re-syncing the same message is idempotent and
 * keeps is_unread current (it changes as the user reads emails).
 */
@Component
public class GmailSyncAdapter {

    private static final Logger log = LoggerFactory.getLogger(GmailSyncAdapter.class);
    private static final String STATE_KEY = "gmail:inbox";

    private final GmailApiClient api;
    private final SyncStateRepository state;
    private final NamedParameterJdbcTemplate jdbc;
    private final UserIntegrationRepository integrationRepo;
    private final GoogleOAuthClient googleAuth;

    GmailSyncAdapter(GmailApiClient api, SyncStateRepository state,
                     NamedParameterJdbcTemplate jdbc,
                     UserIntegrationRepository integrationRepo,
                     GoogleOAuthClient googleAuth) {
        this.api            = api;
        this.state          = state;
        this.jdbc           = jdbc;
        this.integrationRepo = integrationRepo;
        this.googleAuth     = googleAuth;
    }

    /** Per-user sync — gets a fresh access token via the stored refresh token. */
    public SyncResult syncForUser(String clerkUserId) {
        var integration = integrationRepo.findByUserAndProvider(clerkUserId, "google");
        if (integration.isEmpty()) {
            log.debug("Gmail sync skipped for {} — no google integration", clerkUserId);
            return new SyncResult(0);
        }
        String refreshToken = integration.get().refreshToken();
        if (refreshToken == null || refreshToken.isBlank()) {
            log.warn("Gmail sync skipped for {} — no refresh token stored", clerkUserId);
            return new SyncResult(0);
        }
        var accessTokenOpt = googleAuth.getUserAccessToken(refreshToken);
        if (accessTokenOpt.isEmpty()) {
            log.warn("Gmail sync skipped for {} — could not obtain access token", clerkUserId);
            return new SyncResult(0);
        }
        return doSync(clerkUserId, accessTokenOpt.get());
    }

    /** Per-user sync using a pre-obtained access token (avoids an extra token-refresh call). */
    public SyncResult syncForUser(String clerkUserId, String accessToken) {
        return doSync(clerkUserId, accessToken);
    }

    private SyncResult doSync(String clerkUserId, String accessToken) {
        String stateKey = "gmail:" + clerkUserId;
        Instant since = state.getLastSyncedAt(stateKey)
                .orElse(Instant.now().minus(30, ChronoUnit.DAYS));
        Instant syncStart = Instant.now();

        List<GmailMessage> messages = api.fetchMessagesWithToken(accessToken, since);
        if (!messages.isEmpty()) upsertForUser(messages, clerkUserId);

        state.update(stateKey, syncStart);
        integrationRepo.updateLastSynced(clerkUserId, "google");
        log.info("Gmail sync for user {} — {} messages upserted", clerkUserId, messages.size());
        return new SyncResult(messages.size());
    }

    /** Global sync using shared credentials from GoogleProperties (legacy / scheduled). */
    public SyncResult sync() {
        Instant since = state.getLastSyncedAt(STATE_KEY)
                .orElse(Instant.now().minus(30, ChronoUnit.DAYS));
        Instant syncStart = Instant.now();

        log.info("Gmail sync start since {}", since);

        List<GmailMessage> messages = api.fetchMessages(since);
        if (messages.isEmpty()) {
            log.info("Gmail sync done — no new messages");
            state.update(STATE_KEY, syncStart);
            return new SyncResult(0);
        }

        upsert(messages);
        state.update(STATE_KEY, syncStart);
        log.info("Gmail sync done — {} messages upserted", messages.size());
        return new SyncResult(messages.size());
    }

    private void upsert(List<GmailMessage> messages) {
        upsertForUser(messages, "");
    }

    private void upsertForUser(List<GmailMessage> messages, String userId) {
        SqlParameterSource[] batch = messages.stream().map(m -> new MapSqlParameterSource()
                .addValue("id",         m.id())
                .addValue("subject",    m.subject())
                .addValue("sender",     m.sender())
                .addValue("snippet",    m.snippet())
                .addValue("isUnread",   m.isUnread())
                .addValue("receivedAt", m.receivedAt() != null ? m.receivedAt().toString() : null)
                .addValue("importance", m.importance())
                .addValue("userId",     userId))
                .toArray(SqlParameterSource[]::new);

        jdbc.batchUpdate("""
                INSERT INTO gmail.emails (id, user_id, subject, sender, snippet, is_unread, received_at, importance, is_archived)
                VALUES (:id, :userId, :subject, :sender, :snippet, :isUnread,
                        CAST(:receivedAt AS timestamptz), :importance, FALSE)
                ON CONFLICT (id) DO UPDATE
                    SET is_unread   = EXCLUDED.is_unread,
                        snippet     = EXCLUDED.snippet,
                        importance  = EXCLUDED.importance,
                        user_id     = EXCLUDED.user_id
                """, batch);
    }

    public record SyncResult(int messagesUpserted) {}
}