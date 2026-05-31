package org.example.coral.analytics;

import org.example.coral.coral.SchemaCatalog;
import org.example.coral.coral.TableSpec;
import org.example.coral.model.CoralResultSet;
import org.example.coral.model.TimelineEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Builds the unified, time-sorted activity stream across every catalog source.
 *
 * Uses NamedParameterJdbcTemplate directly rather than the AI validation pipeline,
 * because timeline queries are trusted/internal SQL — not AI-generated. This also
 * sidesteps JSqlParser keyword conflicts (e.g. "events" in calendar.events is
 * reserved in JSqlParser's MySQL grammar, causing silent validation failures).
 */
@Service
public class TimelineService {

    private static final Logger log = LoggerFactory.getLogger(TimelineService.class);

    private final SchemaCatalog catalog;
    private final ContextAggregationService aggregation;
    private final NamedParameterJdbcTemplate jdbc;

    public TimelineService(SchemaCatalog catalog, ContextAggregationService aggregation,
                           NamedParameterJdbcTemplate jdbc) {
        this.catalog     = catalog;
        this.aggregation = aggregation;
        this.jdbc        = jdbc;
    }

    public List<TimelineEvent> buildTimeline(String clerkUserId) {
        List<CoralResultSet> results = new ArrayList<>();
        for (Map.Entry<String, TableSpec> entry : catalog.all().entrySet()) {
            String table    = entry.getKey();
            TableSpec spec  = entry.getValue();
            String cols = spec.columns().stream()
                    .filter(c -> !c.equals("user_id"))
                    .collect(Collectors.joining(", "));
            String sql = "SELECT " + cols + " FROM " + table
                    + " WHERE user_id = :uid LIMIT 200";
            try {
                List<Map<String, Object>> rows = jdbc
                        .queryForList(sql, Map.of("uid", clerkUserId))
                        .stream()
                        .map(TimelineService::normalizeTemporal)
                        .collect(Collectors.toList());
                results.add(new CoralResultSet("tl-" + table, table, rows, 0));
            } catch (Exception e) {
                log.error("Timeline source {} query failed: {}", table, e.getMessage());
            }
        }
        return aggregation.aggregate(results);
    }

    /** Convert JDBC temporal types to ISO-8601 strings for consistent JSON serialization. */
    private static Map<String, Object> normalizeTemporal(Map<String, Object> row) {
        Map<String, Object> copy = new HashMap<>(row);
        for (Map.Entry<String, Object> e : copy.entrySet()) {
            Object v = e.getValue();
            if (v instanceof Timestamp ts)          e.setValue(ts.toInstant().toString());
            else if (v instanceof OffsetDateTime odt) e.setValue(odt.toInstant().toString());
            else if (v instanceof Instant i)          e.setValue(i.toString());
            else if (v instanceof Date d)             e.setValue(d.toInstant().toString());
        }
        return copy;
    }
}
