package org.example.coral.controller;

import org.example.coral.sync.GitHubSyncAdapter.SyncResult;
import org.example.coral.sync.SyncScheduler;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Manual sync trigger — useful during development and for debugging. */
@RestController
@RequestMapping("/api/sync")
public class SyncController {

    private final SyncScheduler scheduler;

    public SyncController(SyncScheduler scheduler) {
        this.scheduler = scheduler;
    }

    /**
     * POST /api/sync/github
     * Triggers an immediate GitHub sync and returns per-repo results.
     * Returns 200 with an empty list if the integration is not configured.
     */
    @PostMapping("/github")
    public ResponseEntity<List<SyncResult>> syncGitHub() {
        List<SyncResult> results = scheduler.triggerGitHub();
        return ResponseEntity.ok(results);
    }
}
