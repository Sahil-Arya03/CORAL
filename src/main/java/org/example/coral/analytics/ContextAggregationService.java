package org.example.coral.analytics;

import org.example.coral.model.CoralResultSet;
import org.example.coral.model.TimelineEvent;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Deterministic cross-source aggregation: normalizes raw Coral rows into a unified, time-sorted
 * TimelineEvent stream. Cross-source correlation lives here (not in a federated SQL JOIN).
 */
@Service
public class ContextAggregationService {

    public List<TimelineEvent> aggregate(List<CoralResultSet> results) {
        List<TimelineEvent> events = new ArrayList<>();
        for (CoralResultSet rs : results) {
            for (Map<String, Object> row : rs.rows()) {
                TimelineEvent e = toEvent(rs.table(), row);
                if (e != null) events.add(e);
            }
        }
        events.sort(Comparator.comparing(TimelineEvent::occurredAt).reversed());
        return events;
    }

    private TimelineEvent toEvent(String table, Map<String, Object> row) {
        return switch (table) {
            case "notion.tasks" -> new TimelineEvent("notion", "task",
                    parseTime(row.get("updated_at")),
                    str(row.get("title")),
                    "status=" + str(row.get("status")) + ", due=" + str(row.get("due_date"))
                            + ", project=" + str(row.get("project")),
                    row);
            case "github.commits" -> new TimelineEvent("github", "commit",
                    parseTime(row.get("committed_at")),
                    str(row.get("message")),
                    "repo=" + str(row.get("repo")), row);
            case "github.pull_requests" -> new TimelineEvent("github", "pr",
                    parseTime(row.get("created_at")),
                    str(row.get("title")),
                    "repo=" + str(row.get("repo")) + ", state=" + str(row.get("state")), row);
            case "calendar.events" -> new TimelineEvent("calendar", "event",
                    parseTime(row.get("start_at")),
                    str(row.get("title")),
                    "attendees=" + str(row.get("attendees_count")), row);
            case "gmail.emails" -> new TimelineEvent("gmail", "email",
                    parseTime(row.get("received_at")),
                    str(row.get("subject")),
                    "from=" + str(row.get("sender")) + ", importance=" + str(row.get("importance"))
                            + ", unread=" + str(row.get("is_unread")),
                    row);
            default -> null;
        };
    }

    private Instant parseTime(Object value) {
        if (value == null) return Instant.EPOCH;
        if (value instanceof Timestamp ts)       return ts.toInstant();
        if (value instanceof OffsetDateTime odt) return odt.toInstant();
        if (value instanceof Instant i)          return i;
        try { return Instant.parse(value.toString()); } catch (Exception e) { return Instant.EPOCH; }
    }

    private String str(Object value) {
        return value == null ? "" : value.toString();
    }
}
