package org.example.coral.sync;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Thin wrapper around the Gmail REST API v1.
 * Lists message IDs matching a date filter, then fetches each message's
 * metadata (Subject, From, Date headers + snippet + labelIds).
 * Returns domain records ready for DB upsert — callers never see raw JSON.
 */
@Component
class GmailApiClient {

    private static final Logger log = LoggerFactory.getLogger(GmailApiClient.class);
    private static final String BASE = "https://gmail.googleapis.com/gmail/v1/users/me";
    private static final int MAX_RESULTS = 200;

    private final GoogleOAuthClient auth;
    private final RestClient http;

    GmailApiClient(GoogleOAuthClient auth) {
        this.auth = auth;
        this.http = RestClient.builder().baseUrl(BASE).build();
    }

    /** Fetch messages using a pre-obtained access token (per-user flow). */
    List<GmailMessage> fetchMessagesWithToken(String accessToken, Instant since) {
        return fetchWithBearer("Bearer " + accessToken, since);
    }

    /**
     * Fetch up to MAX_RESULTS messages received on or after {@code since}.
     * Returns empty list when credentials are not configured or any API error occurs.
     */
    List<GmailMessage> fetchMessages(Instant since) {
        Optional<String> token = auth.getAccessToken();
        if (token.isEmpty()) return List.of();

        String bearer = "Bearer " + token.get();
        return fetchWithBearer(bearer, since);
    }

    private List<GmailMessage> fetchWithBearer(String bearer, Instant since) {
        String dateFilter = "after:" + LocalDate.ofInstant(since, java.time.ZoneOffset.UTC)
                .format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));

        List<String> ids = listAllMessageIds(bearer, dateFilter);
        if (ids.isEmpty()) return List.of();

        List<GmailMessage> messages = new ArrayList<>(ids.size());
        for (String id : ids) {
            fetchDetail(bearer, id).ifPresent(messages::add);
        }
        return messages;
    }

    // ── List IDs (paginated) ──────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<String> listAllMessageIds(String bearer, String q) {
        List<String> ids = new ArrayList<>();
        String pageToken = null;
        int pages = 0;

        do {
            try {
                String uri = "/messages?q={q}&maxResults={max}"
                        + (pageToken != null ? "&pageToken={pt}" : "");
                Map<String, Object> resp = pageToken != null
                        ? http.get().uri(uri, q, MAX_RESULTS, pageToken)
                                .header("Authorization", bearer)
                                .retrieve().body(new ParameterizedTypeReference<>() {})
                        : http.get().uri(uri, q, MAX_RESULTS)
                                .header("Authorization", bearer)
                                .retrieve().body(new ParameterizedTypeReference<>() {});

                if (resp == null) break;
                List<Map<String, Object>> items = (List<Map<String, Object>>) resp.get("messages");
                if (items != null) items.stream().map(m -> (String) m.get("id")).forEach(ids::add);
                pageToken = (String) resp.get("nextPageToken");
            } catch (Exception e) {
                log.warn("Gmail list messages failed (page {}): {}", pages + 1, e.getMessage());
                break;
            }
            pages++;
        } while (pageToken != null && pages < 10); // cap at 2 000 messages per sync

        return ids;
    }

    // ── Fetch single message detail ───────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Optional<GmailMessage> fetchDetail(String bearer, String id) {
        try {
            Map<String, Object> msg = http.get()
                    .uri("/messages/{id}?format=metadata&metadataHeaders=Subject&metadataHeaders=From&metadataHeaders=Date",
                            id)
                    .header("Authorization", bearer)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            if (msg == null) return Optional.empty();

            String snippet   = (String) msg.getOrDefault("snippet", "");
            List<String> labels = (List<String>) msg.getOrDefault("labelIds", List.of());
            boolean isUnread = labels.contains("UNREAD");

            Map<String, Object> payload = (Map<String, Object>) msg.get("payload");
            List<Map<String, Object>> headers = payload != null
                    ? (List<Map<String, Object>>) payload.get("headers")
                    : List.of();

            String subject = headerValue(headers, "Subject");
            String from    = headerValue(headers, "From");
            String date    = headerValue(headers, "Date");

            Instant receivedAt = parseRfc2822(date);
            String importance  = deriveImportance(labels, subject);

            return Optional.of(new GmailMessage(id, subject, from, snippet, isUnread, receivedAt, importance));
        } catch (Exception e) {
            log.warn("Gmail fetch message {} failed: {}", id, e.getMessage());
            return Optional.empty();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static String headerValue(List<Map<String, Object>> headers, String name) {
        if (headers == null) return "";
        return headers.stream()
                .filter(h -> name.equalsIgnoreCase((String) h.get("name")))
                .map(h -> (String) h.get("value"))
                .findFirst()
                .orElse("");
    }

    private static Instant parseRfc2822(String date) {
        if (date == null || date.isBlank()) return Instant.now();
        // Strip optional CFWS comment like "(UTC)" that RFC_1123_DATE_TIME rejects
        String cleaned = date.trim().replaceAll("\\s*\\(.*\\)\\s*$", "").trim();
        // Normalise named TZ abbreviations that Java doesn't know
        cleaned = cleaned.replace(" GMT", " +0000")
                         .replace(" UT",  " +0000")
                         .replace(" UTC", " +0000");
        try {
            return java.time.ZonedDateTime.parse(cleaned, DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant();
        } catch (Exception e1) {
            try { return Instant.parse(cleaned); } catch (Exception e2) { return Instant.now(); }
        }
    }

    private static String deriveImportance(List<String> labels, String subject) {
        if (labels.contains("IMPORTANT") || labels.contains("CATEGORY_PERSONAL")) return "high";
        if (labels.contains("CATEGORY_PROMOTIONS") || labels.contains("CATEGORY_UPDATES")) return "low";
        return "normal";
    }

    // ── Domain record ─────────────────────────────────────────────────────────

    record GmailMessage(
            String id,
            String subject,
            String sender,
            String snippet,
            boolean isUnread,
            Instant receivedAt,
            String importance
    ) {}
}