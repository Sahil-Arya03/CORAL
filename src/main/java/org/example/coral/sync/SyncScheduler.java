package org.example.coral.sync;

import org.example.coral.persistence.UserIntegrationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Drives all integration sync jobs on fixed-delay schedules.
 * initialDelay gives the app time to fully start before the first run.
 * Errors inside each adapter are caught and logged — they never abort
 * the scheduler thread or affect other integrations.
 */
@Component
public class SyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(SyncScheduler.class);

    private final GitHubProperties         githubProps;
    private final GoogleProperties         googleProps;
    private final NotionProperties         notionProps;
    private final GitHubSyncAdapter        github;
    private final GmailSyncAdapter         gmail;
    private final CalendarSyncAdapter      calendar;
    private final NotionSyncAdapter        notion;
    private final UserIntegrationRepository integrationRepo;

    SyncScheduler(GitHubProperties githubProps,
                  GoogleProperties googleProps,
                  NotionProperties notionProps,
                  GitHubSyncAdapter github,
                  GmailSyncAdapter gmail,
                  CalendarSyncAdapter calendar,
                  NotionSyncAdapter notion,
                  UserIntegrationRepository integrationRepo) {
        this.githubProps     = githubProps;
        this.googleProps     = googleProps;
        this.notionProps     = notionProps;
        this.github          = github;
        this.gmail           = gmail;
        this.calendar        = calendar;
        this.notion          = notion;
        this.integrationRepo = integrationRepo;
    }

    // ── GitHub ────────────────────────────────────────────────────────────────

    @Scheduled(initialDelayString = "${coral.sync.startup-delay-ms:10000}",
               fixedDelayString   = "${coral.github.sync-interval-ms:1800000}")
    void syncGitHub() {
        // Per-user sweep (uses PATs stored in user_integrations)
        List<String> users = integrationRepo.findUsersByProvider("github");
        if (!users.isEmpty()) {
            log.info("GitHub per-user sweep starting for {} users", users.size());
            users.forEach(uid -> { try { github.syncForUser(uid); } catch (Exception e) { log.error("GitHub sync error for {}: {}", uid, e.getMessage()); } });
        }
        // Legacy global sweep (uses GitHubProperties PAT if configured)
        if (githubProps.isConfigured()) {
            log.info("GitHub global sync starting ({} repos)", githubProps.repos().size());
            githubProps.repos().forEach(github::syncRepo);
        }
    }

    // ── Gmail (per-user sweep) ────────────────────────────────────────────────

    @Scheduled(initialDelayString = "${coral.sync.startup-delay-ms:10000}",
               fixedDelayString   = "${coral.google.gmail.sync-interval-ms:900000}")
    void syncGmail() {
        List<String> users = integrationRepo.findUsersByProvider("google");
        if (users.isEmpty()) { log.debug("Gmail sweep skipped — no users with google integration"); return; }
        log.info("Gmail sweep starting for {} users", users.size());
        users.forEach(uid -> { try { gmail.syncForUser(uid); } catch (Exception e) { log.error("Gmail sync error for {}: {}", uid, e.getMessage()); } });
    }

    // ── Google Calendar (per-user sweep) ─────────────────────────────────────

    @Scheduled(initialDelayString = "${coral.sync.startup-delay-ms:10000}",
               fixedDelayString   = "${coral.google.calendar.sync-interval-ms:1800000}")
    void syncCalendar() {
        List<String> users = integrationRepo.findUsersByProvider("google");
        if (users.isEmpty()) { log.debug("Calendar sweep skipped — no users with google integration"); return; }
        log.info("Calendar sweep starting for {} users", users.size());
        users.forEach(uid -> { try { calendar.syncForUser(uid); } catch (Exception e) { log.error("Calendar sync error for {}: {}", uid, e.getMessage()); } });
    }

    // ── Notion (per-user sweep) ───────────────────────────────────────────────

    @Scheduled(initialDelayString = "${coral.sync.startup-delay-ms:10000}",
               fixedDelayString   = "${coral.notion.sync-interval-ms:600000}")
    void syncNotion() {
        List<String> users = integrationRepo.findUsersByProvider("notion");
        if (users.isEmpty()) { log.debug("Notion sweep skipped — no users with notion integration"); return; }
        log.info("Notion sweep starting for {} users", users.size());
        users.forEach(uid -> { try { notion.syncForUser(uid); } catch (Exception e) { log.error("Notion sync error for {}: {}", uid, e.getMessage()); } });
    }

    // ── Manual triggers (called by SyncController / IntegrationController) ───

    public List<GitHubSyncAdapter.SyncResult> triggerGitHub() {
        if (!githubProps.isConfigured()) return List.of();
        return githubProps.repos().stream().map(github::syncRepo).toList();
    }

    public GitHubSyncAdapter.SyncResult triggerGitHubForUser(String clerkUserId) {
        return github.syncForUser(clerkUserId);
    }

    public GmailSyncAdapter.SyncResult triggerGmailForUser(String clerkUserId) {
        return gmail.syncForUser(clerkUserId);
    }

    public CalendarSyncAdapter.SyncResult triggerCalendarForUser(String clerkUserId) {
        return calendar.syncForUser(clerkUserId);
    }

    public NotionSyncAdapter.SyncResult triggerNotionForUser(String clerkUserId) {
        return notion.syncForUser(clerkUserId);
    }

    /** Legacy global trigger — kept for /api/sync/* endpoints. */
    public GmailSyncAdapter.SyncResult triggerGmail()       { return gmail.sync(); }
    public CalendarSyncAdapter.SyncResult triggerCalendar() { return calendar.sync(); }
    public NotionSyncAdapter.SyncResult triggerNotion()     { return notion.sync(); }
}