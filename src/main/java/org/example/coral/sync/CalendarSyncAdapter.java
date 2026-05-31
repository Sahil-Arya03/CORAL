package org.example.coral.sync;

import org.example.coral.persistence.UserIntegrationRepository;
import org.example.coral.sync.CalendarApiClient.CalendarEvent;
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
 * Orchestrates one Google Calendar sync cycle over a rolling window:
 *   now() - 7 days  →  now() + 60 days
 *
 * Strategy: DELETE existing rows in the window then INSERT all fetched events.
 * This naturally handles cancellations and rescheduled events without needing
 * a tombstone column — the window is always authoritative from Google's side.
 */
@Component
public class CalendarSyncAdapter {

    private static final Logger log = LoggerFactory.getLogger(CalendarSyncAdapter.class);
    private static final String STATE_KEY = "calendar:primary";

    private final CalendarApiClient api;
    private final SyncStateRepository state;
    private final NamedParameterJdbcTemplate jdbc;
    private final UserIntegrationRepository integrationRepo;
    private final GoogleOAuthClient googleAuth;

    CalendarSyncAdapter(CalendarApiClient api, SyncStateRepository state,
                        NamedParameterJdbcTemplate jdbc,
                        UserIntegrationRepository integrationRepo,
                        GoogleOAuthClient googleAuth) {
        this.api             = api;
        this.state           = state;
        this.jdbc            = jdbc;
        this.integrationRepo = integrationRepo;
        this.googleAuth      = googleAuth;
    }

    /** Per-user sync — gets a fresh access token via the stored refresh token. */
    public SyncResult syncForUser(String clerkUserId) {
        var integration = integrationRepo.findByUserAndProvider(clerkUserId, "google");
        if (integration.isEmpty()) {
            log.debug("Calendar sync skipped for {} — no google integration", clerkUserId);
            return new SyncResult(0);
        }
        String refreshToken = integration.get().refreshToken();
        if (refreshToken == null || refreshToken.isBlank()) {
            log.warn("Calendar sync skipped for {} — no refresh token stored", clerkUserId);
            return new SyncResult(0);
        }
        var accessTokenOpt = googleAuth.getUserAccessToken(refreshToken);
        if (accessTokenOpt.isEmpty()) {
            log.warn("Calendar sync skipped for {} — could not obtain access token", clerkUserId);
            return new SyncResult(0);
        }
        return doSync(clerkUserId, accessTokenOpt.get());
    }

    /** Per-user sync using a pre-obtained access token. */
    public SyncResult syncForUser(String clerkUserId, String accessToken) {
        return doSync(clerkUserId, accessToken);
    }

    private SyncResult doSync(String clerkUserId, String accessToken) {
        Instant from = Instant.now().minus(7,  ChronoUnit.DAYS);
        Instant to   = Instant.now().plus(60, ChronoUnit.DAYS);

        List<CalendarEvent> events = api.fetchEventsWithToken(accessToken, from, to);
        replaceWindowForUser(from, to, events, clerkUserId);

        String stateKey = "calendar:" + clerkUserId;
        state.update(stateKey, Instant.now());
        integrationRepo.updateLastSynced(clerkUserId, "google");
        log.info("Calendar sync for user {} — {} events", clerkUserId, events.size());
        return new SyncResult(events.size());
    }

    /** Global sync using shared credentials from GoogleProperties (legacy / scheduled). */
    public SyncResult sync() {
        Instant from  = Instant.now().minus(7,  ChronoUnit.DAYS);
        Instant to    = Instant.now().plus(60, ChronoUnit.DAYS);
        Instant syncStart = Instant.now();

        log.info("Calendar sync start: window {} → {}", from, to);

        List<CalendarEvent> events = api.fetchEvents(from, to);
        if (events.isEmpty()) {
            log.info("Calendar sync done — no events in window");
            state.update(STATE_KEY, syncStart);
            return new SyncResult(0);
        }

        replaceWindow(from, to, events);
        state.update(STATE_KEY, syncStart);
        log.info("Calendar sync done — {} events replaced in window", events.size());
        return new SyncResult(events.size());
    }

    private void replaceWindow(Instant from, Instant to, List<CalendarEvent> events) {
        replaceWindowForUser(from, to, events, "");
    }

    private void replaceWindowForUser(Instant from, Instant to, List<CalendarEvent> events, String userId) {
        jdbc.update("""
                DELETE FROM calendar.events
                WHERE user_id = :uid
                  AND start_at >= CAST(:from AS timestamptz)
                  AND start_at <= CAST(:to   AS timestamptz)
                """,
                new MapSqlParameterSource("uid", userId)
                        .addValue("from", from.toString())
                        .addValue("to", to.toString()));

        if (events.isEmpty()) return;

        SqlParameterSource[] batch = events.stream().map(e -> new MapSqlParameterSource()
                .addValue("id",             e.id() + ":" + userId)  // scoped PK — unique per user
                .addValue("googleEventId",  e.id())                  // raw Google event ID for write-back
                .addValue("userId",         userId)
                .addValue("title",          e.title())
                .addValue("startAt",        e.startAt()  != null ? e.startAt().toString()  : null)
                .addValue("endAt",          e.endAt()    != null ? e.endAt().toString()    : null)
                .addValue("attendees",      e.attendeesCount())
                .addValue("isMeeting",      e.isMeeting())
                .addValue("description",    e.description())
                .addValue("location",       e.location()))
                .toArray(SqlParameterSource[]::new);

        jdbc.batchUpdate("""
                INSERT INTO calendar.events
                    (id, google_event_id, user_id, title, start_at, end_at,
                     attendees_count, is_meeting, description, location)
                VALUES (:id, :googleEventId, :userId, :title,
                        CAST(:startAt AS timestamptz), CAST(:endAt AS timestamptz),
                        :attendees, :isMeeting, :description, :location)
                ON CONFLICT (id) DO UPDATE
                    SET title           = EXCLUDED.title,
                        start_at        = EXCLUDED.start_at,
                        end_at          = EXCLUDED.end_at,
                        attendees_count = EXCLUDED.attendees_count,
                        is_meeting      = EXCLUDED.is_meeting,
                        description     = EXCLUDED.description,
                        location        = EXCLUDED.location,
                        google_event_id = EXCLUDED.google_event_id,
                        user_id         = EXCLUDED.user_id
                """, batch);
    }

    public record SyncResult(int eventsUpserted) {}
}