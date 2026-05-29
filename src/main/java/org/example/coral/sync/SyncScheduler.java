package org.example.coral.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Drives all integration sync jobs on a fixed-delay schedule.
 * initialDelay gives the app time to fully start before the first run.
 * Errors in any individual repo are caught inside GitHubSyncAdapter and logged —
 * they never abort the scheduler thread.
 */
@Component
public class SyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(SyncScheduler.class);

    private final GitHubProperties props;
    private final GitHubSyncAdapter github;

    SyncScheduler(GitHubProperties props, GitHubSyncAdapter github) {
        this.props  = props;
        this.github = github;
    }

    @Scheduled(initialDelayString  = "${coral.sync.startup-delay-ms:10000}",
               fixedDelayString    = "${coral.github.sync-interval-ms:1800000}")
    void syncGitHub() {
        if (!props.isConfigured()) {
            log.debug("GitHub sync skipped — coral.github.token or coral.github.repos not set");
            return;
        }
        log.info("GitHub scheduled sync starting ({} repos)", props.repos().size());
        for (String repo : props.repos()) {
            github.syncRepo(repo);
        }
    }

    /** Called by SyncController for an immediate manual run. Returns per-repo results. */
    public List<GitHubSyncAdapter.SyncResult> triggerGitHub() {
        if (!props.isConfigured()) return List.of();
        return props.repos().stream().map(github::syncRepo).toList();
    }
}
