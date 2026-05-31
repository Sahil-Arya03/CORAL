package org.example.coral.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.example.coral.config.SecurityUtils;
import org.example.coral.persistence.UserIntegrationRepository;
import org.example.coral.persistence.UserIntegrationRepository.UserIntegration;
import org.example.coral.sync.CalendarSyncAdapter;
import org.example.coral.sync.GitHubSyncAdapter;
import org.example.coral.sync.GmailSyncAdapter;
import org.example.coral.sync.GoogleOAuthClient;
import org.example.coral.sync.GoogleProperties;
import org.example.coral.sync.NotionOAuthClient;
import org.example.coral.sync.NotionSyncAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/integrations")
public class IntegrationController {

    private static final Logger log = LoggerFactory.getLogger(IntegrationController.class);

    private static final String GOOGLE_AUTH_BASE =
            "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String GOOGLE_SCOPES =
            "https://www.googleapis.com/auth/gmail.readonly " +
            "https://www.googleapis.com/auth/calendar.events";

    private final UserIntegrationRepository integrationRepo;
    private final GoogleOAuthClient         googleAuth;
    private final GoogleProperties          googleProps;
    private final GmailSyncAdapter          gmailSync;
    private final CalendarSyncAdapter       calendarSync;
    private final NotionSyncAdapter         notionSync;
    private final NotionOAuthClient         notionOAuth;
    private final GitHubSyncAdapter         githubSync;

    @Value("${coral.google.redirect-uri:http://localhost:5173/integrations}")
    private String googleRedirectUri;

    @Value("${coral.notion.redirect-uri:http://localhost:5173/integrations}")
    private String notionRedirectUri;

    public IntegrationController(UserIntegrationRepository integrationRepo,
                                  GoogleOAuthClient googleAuth,
                                  GoogleProperties googleProps,
                                  GmailSyncAdapter gmailSync,
                                  CalendarSyncAdapter calendarSync,
                                  NotionSyncAdapter notionSync,
                                  NotionOAuthClient notionOAuth,
                                  GitHubSyncAdapter githubSync) {
        this.integrationRepo = integrationRepo;
        this.googleAuth      = googleAuth;
        this.googleProps     = googleProps;
        this.gmailSync       = gmailSync;
        this.calendarSync    = calendarSync;
        this.notionSync      = notionSync;
        this.notionOAuth     = notionOAuth;
        this.githubSync      = githubSync;
    }

    // ── GET /api/integrations ─────────────────────────────────────────────────

    @GetMapping
    public List<IntegrationDto> list(HttpServletRequest request) {
        String uid = clerkId(request);
        return integrationRepo.findByUserId(uid).stream()
                .map(IntegrationDto::from)
                .toList();
    }

    // ── GET /api/integrations/status ─────────────────────────────────────────

    @GetMapping("/status")
    public Map<String, Object> status(HttpServletRequest request) {
        String uid = clerkId(request);
        Map<String, Object> out = new LinkedHashMap<>();
        for (String provider : List.of("github", "google", "notion")) {
            var opt = integrationRepo.findByUserAndProvider(uid, provider);
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("connected",    opt.isPresent());
            info.put("lastSyncedAt", opt.map(i -> i.lastSyncedAt() != null
                    ? i.lastSyncedAt().toString() : null).orElse(null));
            if ("notion".equals(provider) && opt.isPresent()) {
                info.put("workspaceName", opt.get().extraString("workspace_name"));
            }
            out.put(provider, info);
        }
        return out;
    }

    // ── POST /api/integrations/github ─────────────────────────────────────────

    @PostMapping("/github")
    public ResponseEntity<IntegrationDto> connectGitHub(
            @RequestBody ConnectGitHubRequest req, HttpServletRequest request) {
        String uid = clerkId(request);
        integrationRepo.upsert(uid, "github", req.token(), null,
                Map.of("repos", req.repos() != null ? req.repos() : List.of()));
        new Thread(() -> {
            try { githubSync.syncForUser(uid); }
            catch (Exception e) { log.warn("Initial GitHub sync failed for {}: {}", uid, e.getMessage()); }
        }).start();
        return integrationRepo.findByUserAndProvider(uid, "github")
                .map(i -> ResponseEntity.ok(IntegrationDto.from(i)))
                .orElse(ResponseEntity.internalServerError().build());
    }

    // ── GET /api/integrations/google/auth-url ────────────────────────────────

    @GetMapping("/google/auth-url")
    public ResponseEntity<Map<String, String>> googleAuthUrl(HttpServletRequest request) {
        if (!googleProps.canOAuth()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Google OAuth credentials not configured on server"));
        }
        String url = GOOGLE_AUTH_BASE
                + "?client_id="     + encode(googleProps.clientId())
                + "&redirect_uri="  + encode(googleRedirectUri)
                + "&response_type=code"
                + "&scope="         + encode(GOOGLE_SCOPES)
                + "&access_type=offline"
                + "&prompt=consent";
        return ResponseEntity.ok(Map.of("url", url));
    }

    // ── POST /api/integrations/google/callback ────────────────────────────────

    @PostMapping("/google/callback")
    public ResponseEntity<IntegrationDto> googleCallback(
            @RequestBody GoogleCallbackRequest req, HttpServletRequest request) {
        String uid = clerkId(request);

        var tokensOpt = googleAuth.exchangeCode(req.code(), googleRedirectUri);
        if (tokensOpt.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        Map<String, Object> tokens = tokensOpt.get();
        String accessToken  = (String) tokens.get("access_token");
        String refreshToken = (String) tokens.get("refresh_token");

        if (accessToken == null) {
            log.error("Google code exchange returned no access_token for user {}", uid);
            return ResponseEntity.badRequest().build();
        }

        integrationRepo.upsert(uid, "google", accessToken, refreshToken, Map.of());

        // Initial sync uses the token we already have — no extra token-refresh round-trip
        final String tokenForSync = accessToken;
        new Thread(() -> {
            try {
                int emails  = gmailSync.syncForUser(uid, tokenForSync).messagesUpserted();
                int events  = calendarSync.syncForUser(uid, tokenForSync).eventsUpserted();
                log.info("Initial Google sync for {} — {} emails, {} calendar events", uid, emails, events);
            } catch (Exception e) {
                log.warn("Initial Google sync failed for {}: {}", uid, e.getMessage());
            }
        }).start();

        return integrationRepo.findByUserAndProvider(uid, "google")
                .map(i -> ResponseEntity.ok(IntegrationDto.from(i)))
                .orElse(ResponseEntity.internalServerError().build());
    }

    // ── GET /api/integrations/notion/auth-url ────────────────────────────────

    @GetMapping("/notion/auth-url")
    public ResponseEntity<Map<String, String>> notionAuthUrl(HttpServletRequest request) {
        if (!notionOAuth.canOAuth()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Notion OAuth credentials not configured on server"));
        }
        return ResponseEntity.ok(Map.of("url", notionOAuth.getAuthorizationUrl(notionRedirectUri)));
    }

    // ── POST /api/integrations/notion/callback ────────────────────────────────

    @PostMapping("/notion/callback")
    public ResponseEntity<Map<String, Object>> notionCallback(
            @RequestBody NotionCallbackRequest req, HttpServletRequest request) {
        String uid = clerkId(request);

        var tokensOpt = notionOAuth.exchangeCode(req.code(), notionRedirectUri);
        if (tokensOpt.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Failed to exchange Notion authorization code"));
        }
        Map<String, Object> tokens = tokensOpt.get();
        String accessToken   = (String) tokens.get("access_token");
        String workspaceId   = (String) tokens.get("workspace_id");
        String workspaceName = (String) tokens.get("workspace_name");

        if (accessToken == null) {
            log.error("Notion code exchange returned no access_token for user {}", uid);
            return ResponseEntity.badRequest().body(Map.of("error", "No access token in Notion response"));
        }

        String databaseId = normalizeNotionDatabaseId(req.databaseId());

        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("database_id",    databaseId    != null ? databaseId    : "");
        extra.put("workspace_id",   workspaceId   != null ? workspaceId   : "");
        extra.put("workspace_name", workspaceName != null ? workspaceName : "");

        integrationRepo.upsert(uid, "notion", accessToken, null, extra);
        log.info("Notion OAuth connected for user {} — workspace: {}", uid, workspaceName);

        new Thread(() -> {
            try { notionSync.syncForUser(uid); }
            catch (Exception e) { log.warn("Initial Notion OAuth sync failed for {}: {}", uid, e.getMessage()); }
        }).start();

        return ResponseEntity.ok(Map.of(
                "connected",     true,
                "workspaceId",   workspaceId   != null ? workspaceId   : "",
                "workspaceName", workspaceName != null ? workspaceName : ""));
    }

    // ── POST /api/integrations/notion (legacy — manual token entry) ───────────

    @PostMapping("/notion")
    public ResponseEntity<IntegrationDto> connectNotion(
            @RequestBody ConnectNotionRequest req, HttpServletRequest request) {
        String uid        = clerkId(request);
        String databaseId = normalizeNotionDatabaseId(req.databaseId());
        integrationRepo.upsert(uid, "notion", req.token(), null,
                Map.of("database_id", databaseId != null ? databaseId : ""));

        new Thread(() -> {
            try { notionSync.syncForUser(uid); }
            catch (Exception e) { log.warn("Initial Notion sync failed for {}: {}", uid, e.getMessage()); }
        }).start();

        return integrationRepo.findByUserAndProvider(uid, "notion")
                .map(i -> ResponseEntity.ok(IntegrationDto.from(i)))
                .orElse(ResponseEntity.internalServerError().build());
    }

    // ── DELETE /api/integrations/{provider} ───────────────────────────────────

    @DeleteMapping("/{provider}")
    public ResponseEntity<Void> disconnect(@PathVariable String provider,
                                           HttpServletRequest request) {
        String uid = clerkId(request);
        integrationRepo.deleteByUserAndProvider(uid, provider);
        return ResponseEntity.noContent().build();
    }

    // ── POST /api/integrations/{provider}/sync ────────────────────────────────

    @PostMapping("/{provider}/sync")
    public ResponseEntity<Map<String, Object>> syncNow(@PathVariable String provider,
                                                        HttpServletRequest request) {
        String uid = clerkId(request);
        int count = switch (provider) {
            case "github" -> { var r = githubSync.syncForUser(uid); yield r.commits() + r.prs(); }
            case "google" -> {
                int g = gmailSync.syncForUser(uid).messagesUpserted();
                int c = calendarSync.syncForUser(uid).eventsUpserted();
                yield g + c;
            }
            case "notion" -> notionSync.syncForUser(uid).tasksUpserted();
            default       -> 0;
        };
        return ResponseEntity.ok(Map.of("provider", provider, "itemsSynced", count));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String clerkId(HttpServletRequest request) {
        return SecurityUtils.getClerkUserId(request);
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /**
     * Notion database IDs are 32-char hex UUIDs.  Users often paste the full page URL
     * (e.g. https://notion.so/workspace/Title-abc123...32chars?v=...) — extract the UUID.
     * Accepts: plain UUID with or without hyphens, full Notion URL.
     */
    private static String normalizeNotionDatabaseId(String input) {
        if (input == null || input.isBlank()) return input;
        var m = java.util.regex.Pattern
                .compile("[0-9a-f]{8}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{12}",
                         java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(input.trim());
        return m.find() ? m.group() : input.trim();
    }

    // ── DTOs ──────────────────────────────────────────────────────────────────

    public record IntegrationDto(
            String provider,
            boolean connected,
            String connectedAt,
            String lastSyncedAt
    ) {
        static IntegrationDto from(UserIntegration i) {
            return new IntegrationDto(
                    i.provider(),
                    true,
                    i.connectedAt()   != null ? i.connectedAt().toString()   : null,
                    i.lastSyncedAt()  != null ? i.lastSyncedAt().toString()  : null);
        }
    }

    public record ConnectGitHubRequest(String token, List<String> repos) {}
    public record ConnectNotionRequest(String token, String databaseId) {}
    public record GoogleCallbackRequest(String code) {}
    public record NotionCallbackRequest(String code, String databaseId) {}
}