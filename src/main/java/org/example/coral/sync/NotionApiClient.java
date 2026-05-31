package org.example.coral.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Thin wrapper around the Notion API v1.
 * Queries a database for pages edited since a given timestamp, handles
 * Notion's cursor-based pagination, and maps the verbose property format
 * into flat domain records ready for DB upsert.
 */
@Component
class NotionApiClient {

    private static final Logger log = LoggerFactory.getLogger(NotionApiClient.class);
    private static final String BASE          = "https://api.notion.com/v1";
    private static final String NOTION_VERSION = "2022-06-28";
    private static final int    PAGE_SIZE     = 100;

    private final NotionProperties props;
    private final RestClient http;

    NotionApiClient(NotionProperties props) {
        this.props = props;
        this.http  = RestClient.builder()
                .baseUrl(BASE)
                .defaultHeader("Notion-Version", NOTION_VERSION)
                .build();
    }

    /** Query using explicit per-user credentials (token + databaseId). */
    List<NotionTask> queryDatabaseWithCredentials(String token, String databaseId, Instant since) {
        return queryWithCredentials(token, databaseId, since);
    }

    /**
     * Query the configured database for pages last edited after {@code since}.
     * Returns empty list when credentials are not configured or on any error.
     */
    List<NotionTask> queryDatabase(Instant since) {
        if (!props.isConfigured()) return List.of();

        return queryWithCredentials(props.token(), props.databaseId(), since);
    }

    private List<NotionTask> queryWithCredentials(String token, String databaseId, Instant since) {
        List<NotionTask> all = new ArrayList<>();
        String cursor = null;

        do {
            var result = fetchPage(token, databaseId, since, cursor);
            if (result == null) break;
            all.addAll(result.tasks());
            cursor = result.nextCursor();
        } while (cursor != null);

        return all;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private PageResult fetchPage(String token, String databaseId, Instant since, String cursor) {
        try {
            var filterClause = Map.of(
                    "timestamp", "last_edited_time",
                    "last_edited_time", Map.of("after", since.toString())
            );
            var body = new java.util.LinkedHashMap<String, Object>();
            body.put("filter", filterClause);
            body.put("page_size", PAGE_SIZE);
            if (cursor != null) body.put("start_cursor", cursor);

            Map<String, Object> resp = http.post()
                    .uri("/databases/{id}/query", databaseId)
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});

            if (resp == null) return null;

            List<Map<String, Object>> results = (List<Map<String, Object>>) resp.get("results");
            List<NotionTask> tasks = results == null ? List.of()
                    : results.stream().map(NotionApiClient::toTask).toList();

            Boolean hasMore  = (Boolean) resp.get("has_more");
            String nextCursor = Boolean.TRUE.equals(hasMore) ? (String) resp.get("next_cursor") : null;

            return new PageResult(tasks, nextCursor);
        } catch (Exception e) {
            log.warn("Notion database query failed: {}", e.getMessage());
            return null;
        }
    }

    // ── Notion property mapper ────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static NotionTask toTask(Map<String, Object> page) {
        String id          = (String) page.get("id");
        String lastEdited  = (String) page.getOrDefault("last_edited_time", Instant.now().toString());
        Instant updatedAt  = parseInstant(lastEdited);

        Map<String, Object> props = (Map<String, Object>) page.getOrDefault("properties", Map.of());

        String title    = extractTitle(props, "Name", "Title", "Task");
        String status   = extractSelect(props, "Status");
        String priority = extractSelect(props, "Priority");
        String dueDate  = extractDate(props, "Due", "Due Date", "Deadline");
        String project  = extractSelect(props, "Project", "Category", "Tag");

        return new NotionTask(id, title, status, priority, dueDate, project, updatedAt);
    }

    @SuppressWarnings("unchecked")
    private static String extractTitle(Map<String, Object> props, String... keys) {
        for (String key : keys) {
            var prop = (Map<String, Object>) props.get(key);
            if (prop == null) continue;
            var titleList = (List<Map<String, Object>>) prop.get("title");
            if (titleList != null && !titleList.isEmpty()) {
                var textObj = (Map<String, Object>) titleList.get(0).get("text");
                if (textObj != null) return (String) textObj.getOrDefault("content", "");
            }
        }
        return "(untitled)";
    }

    @SuppressWarnings("unchecked")
    private static String extractSelect(Map<String, Object> props, String... keys) {
        for (String key : keys) {
            var prop = (Map<String, Object>) props.get(key);
            if (prop == null) continue;
            var select = (Map<String, Object>) prop.get("select");
            if (select != null) return ((String) select.getOrDefault("name", "")).toLowerCase();
            // multi_select — take first value
            var multi = (List<Map<String, Object>>) prop.get("multi_select");
            if (multi != null && !multi.isEmpty()) {
                return ((String) multi.get(0).getOrDefault("name", "")).toLowerCase();
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static String extractDate(Map<String, Object> props, String... keys) {
        for (String key : keys) {
            var prop = (Map<String, Object>) props.get(key);
            if (prop == null) continue;
            var dateObj = (Map<String, Object>) prop.get("date");
            if (dateObj != null) return (String) dateObj.get("start");
        }
        return null;
    }

    private static Instant parseInstant(String s) {
        if (s == null || s.isBlank()) return Instant.now();
        try { return Instant.parse(s); } catch (Exception e) { return Instant.now(); }
    }

    // ── Inner types ───────────────────────────────────────────────────────────

    record NotionTask(
            String id,
            String title,
            String status,
            String priority,
            String dueDate,
            String project,
            Instant updatedAt
    ) {}

    private record PageResult(List<NotionTask> tasks, String nextCursor) {}
}