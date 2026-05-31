package org.example.coral.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.example.coral.config.SecurityUtils;
import org.example.coral.persistence.UserIntegrationRepository;
import org.example.coral.sync.CalendarApiClient;
import org.example.coral.sync.CalendarApiClient.CalendarApiException;
import org.example.coral.sync.CalendarApiClient.CalendarEvent;
import org.example.coral.sync.CalendarApiClient.EventRequest;
import org.example.coral.sync.CalendarSyncAdapter;
import org.example.coral.sync.GoogleOAuthClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

/**
 * CRUD for Google Calendar events.
 * Every write goes to Google Calendar first, then triggers a fresh sync of the
 * rolling window so the local DB (and therefore the timeline) stays in sync.
 */
@RestController
@RequestMapping("/api/calendar/events")
public class CalendarController {

    private static final Logger log = LoggerFactory.getLogger(CalendarController.class);

    private static final String SCOPE_ERROR_MSG =
            "Your Google connection was made with read-only access. " +
            "Please go to Integrations, disconnect Google, and reconnect to grant calendar write permission.";

    private final CalendarApiClient         calendarApi;
    private final CalendarSyncAdapter       calendarSync;
    private final GoogleOAuthClient         googleAuth;
    private final UserIntegrationRepository integrationRepo;
    private final NamedParameterJdbcTemplate jdbc;

    public CalendarController(CalendarApiClient calendarApi,
                               CalendarSyncAdapter calendarSync,
                               GoogleOAuthClient googleAuth,
                               UserIntegrationRepository integrationRepo,
                               NamedParameterJdbcTemplate jdbc) {
        this.calendarApi     = calendarApi;
        this.calendarSync    = calendarSync;
        this.googleAuth      = googleAuth;
        this.integrationRepo = integrationRepo;
        this.jdbc            = jdbc;
    }

    // ── POST /api/calendar/events ─────────────────────────────────────────────

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(
            @RequestBody EventRequest req, HttpServletRequest httpRequest) {
        String uid = clerkId(httpRequest);
        String accessToken = getAccessToken(uid).orElse(null);
        if (accessToken == null) return notConnected();

        try {
            CalendarEvent ev = calendarApi.createEvent(accessToken, req);
            log.info("Calendar event created in Google: {} for user {}", ev.id(), uid);
            syncBack(uid, accessToken); // refresh local DB from Google
            return ResponseEntity.ok(toDto(ev));
        } catch (CalendarApiException e) {
            return calendarError(e);
        }
    }

    // ── PATCH /api/calendar/events/{id} ──────────────────────────────────────

    @PatchMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(
            @PathVariable String id,
            @RequestBody EventRequest req, HttpServletRequest httpRequest) {
        String uid = clerkId(httpRequest);
        String googleEventId = resolveGoogleEventId(id, uid);
        if (googleEventId == null) return ResponseEntity.notFound().build();

        String accessToken = getAccessToken(uid).orElse(null);
        if (accessToken == null) return notConnected();

        try {
            CalendarEvent ev = calendarApi.updateEvent(accessToken, googleEventId, req);
            log.info("Calendar event updated in Google: {} for user {}", googleEventId, uid);
            syncBack(uid, accessToken);
            return ResponseEntity.ok(toDto(ev));
        } catch (CalendarApiException e) {
            return calendarError(e);
        }
    }

    // ── DELETE /api/calendar/events/{id} ─────────────────────────────────────

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable String id, HttpServletRequest httpRequest) {
        String uid = clerkId(httpRequest);
        String googleEventId = resolveGoogleEventId(id, uid);
        if (googleEventId == null) return ResponseEntity.notFound().build();

        String accessToken = getAccessToken(uid).orElse(null);
        if (accessToken == null) return ResponseEntity.status(503).build();

        try {
            calendarApi.deleteEvent(accessToken, googleEventId);
            log.info("Calendar event deleted from Google: {} for user {}", googleEventId, uid);
            syncBack(uid, accessToken);
            return ResponseEntity.noContent().build();
        } catch (CalendarApiException e) {
            return ResponseEntity.status(e.getStatus()).build();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Re-syncs the rolling calendar window for this user using the token we already have.
     * This ensures the local DB matches Google after every write without a separate
     * token-refresh round-trip.
     */
    private void syncBack(String uid, String accessToken) {
        try {
            calendarSync.syncForUser(uid, accessToken);
        } catch (Exception e) {
            log.warn("Post-write calendar sync failed for {}: {}", uid, e.getMessage());
        }
    }

    private Optional<String> getAccessToken(String uid) {
        return integrationRepo.findByUserAndProvider(uid, "google")
                .map(UserIntegrationRepository.UserIntegration::refreshToken)
                .filter(rt -> rt != null && !rt.isBlank())
                .flatMap(googleAuth::getUserAccessToken);
    }

    private String resolveGoogleEventId(String id, String uid) {
        try {
            var rows = jdbc.queryForList(
                    "SELECT google_event_id FROM calendar.events WHERE id = :id AND user_id = :uid",
                    new MapSqlParameterSource("id", id).addValue("uid", uid));
            if (!rows.isEmpty()) {
                Object gid = rows.get(0).get("google_event_id");
                if (gid != null) return gid.toString();
            }
            return id; // fallback: id might already be the google event id
        } catch (Exception e) {
            return id;
        }
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map<String, Object>> calendarError(CalendarApiException e) {
        if (e.getStatus() == 403) {
            return ResponseEntity.status(403)
                    .body(Map.of("error", SCOPE_ERROR_MSG, "code", "insufficient_scope"));
        }
        return ResponseEntity.status(e.getStatus() > 0 ? e.getStatus() : 502)
                .body(Map.of("error", e.getMessage() != null ? e.getMessage() : "Google Calendar error"));
    }

    private static ResponseEntity<Map<String, Object>> notConnected() {
        return ResponseEntity.status(503)
                .body(Map.of("error", "Google Calendar is not connected. Go to Integrations to connect it.",
                             "code", "not_connected"));
    }

    private static Map<String, Object> toDto(CalendarEvent ev) {
        return Map.of(
                "id",          ev.id(),
                "title",       ev.title()       != null ? ev.title()       : "",
                "startAt",     ev.startAt()     != null ? ev.startAt().toString()     : "",
                "endAt",       ev.endAt()       != null ? ev.endAt().toString()       : "",
                "description", ev.description() != null ? ev.description() : "",
                "location",    ev.location()    != null ? ev.location()    : ""
        );
    }

    private static String clerkId(HttpServletRequest req) {
        try { return SecurityUtils.getClerkUserId(req); } catch (Exception e) { return "dev_user"; }
    }
}
