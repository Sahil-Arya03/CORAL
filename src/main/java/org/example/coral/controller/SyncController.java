package org.example.coral.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.example.coral.config.SecurityUtils;
import org.example.coral.sync.CalendarSyncAdapter;
import org.example.coral.sync.GitHubSyncAdapter.SyncResult;
import org.example.coral.sync.GmailSyncAdapter;
import org.example.coral.sync.NotionSyncAdapter;
import org.example.coral.sync.SyncScheduler;
import org.example.coral.sync.SyncStateRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Manual sync triggers and integration status — useful during development and for the UI. */
@RestController
@RequestMapping("/api/sync")
public class SyncController {

    private final SyncScheduler       scheduler;
    private final SyncStateRepository syncState;

    public SyncController(SyncScheduler scheduler, SyncStateRepository syncState) {
        this.scheduler = scheduler;
        this.syncState = syncState;
    }

    /** POST /api/sync/github — immediate GitHub sync; returns per-repo results. */
    @PostMapping("/github")
    public ResponseEntity<List<SyncResult>> syncGitHub() {
        return ResponseEntity.ok(scheduler.triggerGitHub());
    }

    /** POST /api/sync/gmail — immediate Gmail sync for the calling user. */
    @PostMapping("/gmail")
    public ResponseEntity<GmailSyncAdapter.SyncResult> syncGmail(HttpServletRequest request) {
        return ResponseEntity.ok(scheduler.triggerGmailForUser(uid(request)));
    }

    /** POST /api/sync/calendar — immediate Google Calendar sync for the calling user. */
    @PostMapping("/calendar")
    public ResponseEntity<CalendarSyncAdapter.SyncResult> syncCalendar(HttpServletRequest request) {
        return ResponseEntity.ok(scheduler.triggerCalendarForUser(uid(request)));
    }

    /** POST /api/sync/notion — immediate Notion sync for the calling user. */
    @PostMapping("/notion")
    public ResponseEntity<NotionSyncAdapter.SyncResult> syncNotion(HttpServletRequest request) {
        return ResponseEntity.ok(scheduler.triggerNotionForUser(uid(request)));
    }

    private static String uid(HttpServletRequest request) {
        return SecurityUtils.getClerkUserId(request);
    }

    /**
     * GET /api/sync/status — returns last_synced_at for every integration.
     * Used by the Integrations page to show connection health at a glance.
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : List.of("github:*", "gmail:inbox", "calendar:primary", "notion:tasks")) {
            // github has per-repo keys; summarise as the most recent
            if (key.equals("github:*")) {
                out.put("github", Map.of("lastSyncedAt", "n/a"));
                continue;
            }
            Optional<Instant> last = syncState.getLastSyncedAt(key);
            String integration = key.contains(":") ? key.split(":")[0] : key;
            out.put(integration, Map.of(
                    "lastSyncedAt", last.map(Instant::toString).orElse(null),
                    "synced",       last.isPresent()
            ));
        }
        return ResponseEntity.ok(out);
    }
}