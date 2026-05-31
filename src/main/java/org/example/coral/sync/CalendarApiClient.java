package org.example.coral.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Thin wrapper around the Google Calendar REST API v3.
 * Fetches all events from the primary calendar within a time window,
 * handling pagination automatically.
 * Returns domain records ready for DB upsert — callers never see raw JSON.
 */
@Component
public class CalendarApiClient {

    private static final Logger log = LoggerFactory.getLogger(CalendarApiClient.class);
    private static final String BASE = "https://www.googleapis.com/calendar/v3";
    private static final int MAX_RESULTS = 250;

    private final GoogleOAuthClient auth;
    private final RestClient http;

    CalendarApiClient(GoogleOAuthClient auth) {
        this.auth = auth;
        this.http = RestClient.builder().baseUrl(BASE).build();
    }

    /** Fetch events using a pre-obtained access token (per-user flow). */
    List<CalendarEvent> fetchEventsWithToken(String accessToken, Instant from, Instant to) {
        return fetchWithBearer("Bearer " + accessToken, from, to);
    }

    /**
     * Fetch all events between {@code from} and {@code to} from the primary calendar.
     * Handles pagination. Returns empty list when credentials are not configured or on error.
     */
    @SuppressWarnings("unchecked")
    List<CalendarEvent> fetchEvents(Instant from, Instant to) {
        Optional<String> token = auth.getAccessToken();
        if (token.isEmpty()) return List.of();

        String bearer = "Bearer " + token.get();
        return fetchWithBearer(bearer, from, to);
    }

    @SuppressWarnings("unchecked")
    private List<CalendarEvent> fetchWithBearer(String bearer, Instant from, Instant to) {
        List<CalendarEvent> all = new ArrayList<>();
        String pageToken = null;

        do {
            try {
                String uri = "/calendars/primary/events"
                        + "?singleEvents=true"
                        + "&orderBy=startTime"
                        + "&maxResults=" + MAX_RESULTS
                        + "&timeMin=" + from.toString()
                        + "&timeMax=" + to.toString()
                        + (pageToken != null ? "&pageToken=" + pageToken : "");

                Map<String, Object> resp = http.get()
                        .uri(uri)
                        .header("Authorization", bearer)
                        .retrieve()
                        .body(new ParameterizedTypeReference<>() {});

                if (resp == null) break;

                List<Map<String, Object>> items = (List<Map<String, Object>>) resp.get("items");
                if (items != null) {
                    items.stream().map(CalendarApiClient::toEvent).forEach(all::add);
                }
                pageToken = (String) resp.get("nextPageToken");
            } catch (Exception e) {
                log.warn("Calendar fetch failed: {}", e.getMessage());
                break;
            }
        } while (pageToken != null);

        return all;
    }

    // ── Mapper ────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static CalendarEvent toEvent(Map<String, Object> item) {
        String id    = (String) item.get("id");
        String title = (String) item.getOrDefault("summary", "(no title)");
        String desc  = (String) item.get("description");
        String loc   = (String) item.get("location");

        Instant startAt = parseEventTime((Map<String, Object>) item.get("start"));
        Instant endAt   = parseEventTime((Map<String, Object>) item.get("end"));

        List<Object> attendees = (List<Object>) item.getOrDefault("attendees", List.of());
        int attendeeCount = attendees.size();
        boolean isMeeting = attendeeCount > 1 || item.containsKey("conferenceData");

        return new CalendarEvent(id, title, startAt, endAt, attendeeCount, isMeeting, desc, loc);
    }

    private static Instant parseEventTime(Map<String, Object> timeObj) {
        if (timeObj == null) return Instant.now();
        String dateTime = (String) timeObj.get("dateTime");
        if (dateTime != null) {
            try { return Instant.parse(dateTime); } catch (Exception ignored) {}
            try { return OffsetDateTime.parse(dateTime).toInstant(); } catch (Exception ignored) {}
        }
        String date = (String) timeObj.get("date");
        if (date != null) {
            try { return LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).toInstant(); }
            catch (Exception ignored) {}
        }
        return Instant.now();
    }

    // ── Write operations ──────────────────────────────────────────────────────

    /**
     * Create a new event in the user's primary calendar.
     * Returns the created CalendarEvent (with the Google-assigned ID), or empty on error.
     */
    /**
     * Create an event. Throws {@link CalendarApiException} on Google API errors so the
     * controller can return the correct HTTP status and message to the frontend.
     */
    @SuppressWarnings("unchecked")
    public CalendarEvent createEvent(String accessToken, EventRequest req) {
        try {
            Map<String, Object> body = buildEventBody(req);
            Map<String, Object> resp = http.post()
                    .uri("/calendars/primary/events")
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json")
                    .body(body)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            if (resp == null) throw new CalendarApiException(500, "Google returned empty response");
            return toEvent(resp);
        } catch (CalendarApiException e) {
            throw e;
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            log.warn("Calendar createEvent HTTP {}: {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new CalendarApiException(e.getStatusCode().value(), e.getResponseBodyAsString());
        } catch (Exception e) {
            log.warn("Calendar createEvent failed: {}", e.getMessage());
            throw new CalendarApiException(502, e.getMessage());
        }
    }

    /** Update an existing event. Throws {@link CalendarApiException} on Google API errors. */
    @SuppressWarnings("unchecked")
    public CalendarEvent updateEvent(String accessToken, String googleEventId, EventRequest req) {
        try {
            Map<String, Object> body = buildEventBody(req);
            Map<String, Object> resp = http.patch()
                    .uri("/calendars/primary/events/{id}", googleEventId)
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json")
                    .body(body)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            if (resp == null) throw new CalendarApiException(500, "Google returned empty response");
            return toEvent(resp);
        } catch (CalendarApiException e) {
            throw e;
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            log.warn("Calendar updateEvent {} HTTP {}: {}", googleEventId, e.getStatusCode(), e.getResponseBodyAsString());
            throw new CalendarApiException(e.getStatusCode().value(), e.getResponseBodyAsString());
        } catch (Exception e) {
            log.warn("Calendar updateEvent {} failed: {}", googleEventId, e.getMessage());
            throw new CalendarApiException(502, e.getMessage());
        }
    }

    /** Delete a Google Calendar event. Throws {@link CalendarApiException} on Google API errors. */
    public void deleteEvent(String accessToken, String googleEventId) {
        try {
            http.delete()
                    .uri("/calendars/primary/events/{id}", googleEventId)
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve()
                    .toBodilessEntity();
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            log.warn("Calendar deleteEvent {} HTTP {}: {}", googleEventId, e.getStatusCode(), e.getResponseBodyAsString());
            throw new CalendarApiException(e.getStatusCode().value(), e.getResponseBodyAsString());
        } catch (Exception e) {
            log.warn("Calendar deleteEvent {} failed: {}", googleEventId, e.getMessage());
            throw new CalendarApiException(502, e.getMessage());
        }
    }

    /** Carries the Google API HTTP status and body for upstream error handling. */
    public static class CalendarApiException extends RuntimeException {
        private final int status;
        public CalendarApiException(int status, String message) {
            super(message);
            this.status = status;
        }
        public int getStatus() { return status; }
    }

    private static Map<String, Object> buildEventBody(EventRequest req) {
        return Map.of(
                "summary",     req.title() != null ? req.title() : "",
                "description", req.description() != null ? req.description() : "",
                "location",    req.location() != null ? req.location() : "",
                "start",       Map.of("dateTime", req.startAt(), "timeZone", "UTC"),
                "end",         Map.of("dateTime", req.endAt(),   "timeZone", "UTC")
        );
    }

    // ── Domain records ────────────────────────────────────────────────────────

    public record EventRequest(String title, String startAt, String endAt,
                               String description, String location) {}

    public record CalendarEvent(
            String id,
            String title,
            Instant startAt,
            Instant endAt,
            int attendeesCount,
            boolean isMeeting,
            String description,
            String location
    ) {}
}