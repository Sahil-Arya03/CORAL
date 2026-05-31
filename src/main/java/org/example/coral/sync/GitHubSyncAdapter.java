package org.example.coral.sync;

import org.example.coral.persistence.UserIntegrationRepository;
import org.example.coral.sync.GitHubApiClient.CommitItem;
import org.example.coral.sync.GitHubApiClient.PullItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrates one full GitHub sync cycle:
 *   1. Read last_synced_at from creatoros.sync_state
 *   2. Page through commits (filtered server-side via ?since=)
 *   3. Page through PRs (sorted by updated_at DESC, stop when page predates since)
 *   4. Batch-upsert into github.commits / github.pull_requests
 *   5. Write new last_synced_at to sync_state
 *
 * Commits use INSERT ... ON CONFLICT DO NOTHING (immutable once pushed).
 * PRs use INSERT ... ON CONFLICT DO UPDATE for state and merged_at changes.
 */
@Component
public class GitHubSyncAdapter {

    private static final Logger log = LoggerFactory.getLogger(GitHubSyncAdapter.class);
    private static final int MAX_PAGES = 10; // 1 000 items per sync cycle max

    private final GitHubApiClient             api;
    private final SyncStateRepository         state;
    private final NamedParameterJdbcTemplate  jdbc;
    private final UserIntegrationRepository   integrationRepo;

    GitHubSyncAdapter(GitHubApiClient api, SyncStateRepository state,
                      NamedParameterJdbcTemplate jdbc,
                      UserIntegrationRepository integrationRepo) {
        this.api             = api;
        this.state           = state;
        this.jdbc            = jdbc;
        this.integrationRepo = integrationRepo;
    }

    /**
     * Per-user sync: reads the PAT and repo list stored in user_integrations
     * and syncs each repo, tagging all rows with the user's Clerk user_id.
     */
    public SyncResult syncForUser(String clerkUserId) {
        var integration = integrationRepo.findByUserAndProvider(clerkUserId, "github");
        if (integration.isEmpty()) {
            log.debug("GitHub sync skipped for {} — no github integration", clerkUserId);
            return SyncResult.skipped("(no integration)");
        }
        String pat   = integration.get().accessToken();
        List<String> repos = integration.get().extraAsList("repos");
        if (pat == null || pat.isBlank() || repos.isEmpty()) {
            log.warn("GitHub sync skipped for {} — missing PAT or repos", clerkUserId);
            return SyncResult.skipped("(missing credentials)");
        }
        int totalCommits = 0, totalPrs = 0;
        for (String ownerRepo : repos) {
            SyncResult r = syncRepoForUser(ownerRepo, pat, clerkUserId);
            totalCommits += r.commits();
            totalPrs     += r.prs();
        }
        integrationRepo.updateLastSynced(clerkUserId, "github");
        log.info("GitHub per-user sync for {} — {} commits, {} PRs across {} repos",
                clerkUserId, totalCommits, totalPrs, repos.size());
        return new SyncResult("(per-user)", totalCommits, totalPrs, false);
    }

    private SyncResult syncRepoForUser(String ownerRepo, String pat, String clerkUserId) {
        String[] parts = ownerRepo.split("/", 2);
        if (parts.length != 2) return SyncResult.skipped(ownerRepo);
        String owner = parts[0], repo = parts[1];
        String stateKey = "github:" + ownerRepo + ":" + clerkUserId;

        Instant since     = state.getLastSyncedAt(stateKey)
                .orElse(Instant.now().minus(30, ChronoUnit.DAYS));
        Instant syncStart = Instant.now();

        int commits = syncCommitsForUser(owner, repo, ownerRepo, since, pat, clerkUserId);
        int prs     = syncPullsForUser(owner, repo, ownerRepo, since, pat, clerkUserId);

        state.update(stateKey, syncStart);
        return new SyncResult(ownerRepo, commits, prs, false);
    }

    /** Global sync using the shared PAT from GitHubProperties (legacy / scheduled). */
    SyncResult syncRepo(String ownerRepo) {
        String[] parts = ownerRepo.split("/", 2);
        if (parts.length != 2) {
            log.warn("Skipping invalid repo format: {}", ownerRepo);
            return SyncResult.skipped(ownerRepo);
        }
        String owner = parts[0], repo = parts[1];
        String stateKey = "github:" + ownerRepo;

        Instant since = state.getLastSyncedAt(stateKey)
                .orElse(Instant.now().minus(30, ChronoUnit.DAYS));
        Instant syncStart = Instant.now();

        log.info("GitHub sync start: {} since {}", ownerRepo, since);

        int commits = syncCommits(owner, repo, ownerRepo, since);
        int prs     = syncPulls(owner, repo, ownerRepo, since);

        state.update(stateKey, syncStart);
        log.info("GitHub sync done: {} — {} commits, {} PRs", ownerRepo, commits, prs);
        return new SyncResult(ownerRepo, commits, prs, false);
    }

    // ── Commits ───────────────────────────────────────────────────────────────

    private int syncCommits(String owner, String repo, String ownerRepo, Instant since) {
        return syncCommitsForUser(owner, repo, ownerRepo, since, null, "");
    }

    private int syncCommitsForUser(String owner, String repo, String ownerRepo,
                                   Instant since, String pat, String userId) {
        int total = 0;
        for (int page = 1; page <= MAX_PAGES; page++) {
            List<CommitItem> items = pat != null
                    ? api.fetchCommitsWithToken(owner, repo, since, page, pat)
                    : api.fetchCommits(owner, repo, since, page);
            if (items.isEmpty()) break;
            upsertCommits(items, ownerRepo, userId);
            total += items.size();
            if (items.size() < 100) break;
        }
        return total;
    }

    private void upsertCommits(List<CommitItem> items, String repo, String userId) {
        SqlParameterSource[] batch = items.stream().map(c -> new MapSqlParameterSource()
                .addValue("sha",    c.sha())
                .addValue("repo",   repo)
                .addValue("author", c.author())
                .addValue("msg",    c.message())
                .addValue("at",     c.committedAt() != null ? c.committedAt().toString() : null)
                .addValue("uid",    userId))
                .toArray(SqlParameterSource[]::new);
        jdbc.batchUpdate("""
                INSERT INTO github.commits (sha, repo, author, message, committed_at, user_id)
                VALUES (:sha, :repo, :author, :msg, CAST(:at AS timestamptz), :uid)
                ON CONFLICT (sha) DO UPDATE
                    SET user_id = EXCLUDED.user_id
                """, batch);
    }

    // ── Pull Requests ─────────────────────────────────────────────────────────

    private int syncPulls(String owner, String repo, String ownerRepo, Instant since) {
        return syncPullsForUser(owner, repo, ownerRepo, since, null, "");
    }

    private int syncPullsForUser(String owner, String repo, String ownerRepo,
                                 Instant since, String pat, String userId) {
        int total = 0;
        for (int page = 1; page <= MAX_PAGES; page++) {
            List<PullItem> items = pat != null
                    ? api.fetchPullsWithToken(owner, repo, page, pat)
                    : api.fetchPulls(owner, repo, page);
            if (items.isEmpty()) break;

            List<PullItem> relevant = items.stream()
                    .filter(p -> p.updatedAt() != null && p.updatedAt().isAfter(since))
                    .toList();
            if (!relevant.isEmpty()) {
                upsertPulls(relevant, ownerRepo, userId);
                total += relevant.size();
            }
            if (items.size() < 100 || relevant.isEmpty()) break;
        }
        return total;
    }

    private void upsertPulls(List<PullItem> items, String repo, String userId) {
        SqlParameterSource[] batch = items.stream().map(p -> new MapSqlParameterSource()
                .addValue("id",        p.id())
                .addValue("repo",      repo)
                .addValue("title",     p.title())
                .addValue("state",     p.state())
                .addValue("createdAt", p.createdAt() != null ? p.createdAt().toString() : null)
                .addValue("mergedAt",  p.mergedAt()  != null ? p.mergedAt().toString()  : null)
                .addValue("uid",       userId))
                .toArray(SqlParameterSource[]::new);
        jdbc.batchUpdate("""
                INSERT INTO github.pull_requests (id, repo, title, state, created_at, merged_at, user_id)
                VALUES (:id, :repo, :title, :state,
                        CAST(:createdAt AS timestamptz), CAST(:mergedAt AS timestamptz), :uid)
                ON CONFLICT (id) DO UPDATE
                    SET state     = EXCLUDED.state,
                        merged_at = EXCLUDED.merged_at,
                        user_id   = EXCLUDED.user_id
                """, batch);
    }

    // ── Result type ───────────────────────────────────────────────────────────

    public record SyncResult(String repo, int commits, int prs, boolean skipped) {
        public static SyncResult skipped(String repo) { return new SyncResult(repo, 0, 0, true); }
    }
}
