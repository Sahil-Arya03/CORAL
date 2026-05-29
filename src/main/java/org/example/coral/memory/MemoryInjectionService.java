package org.example.coral.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Array;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Long-term AI memory, ranked by importance + recency (no vectors, no embeddings). Backed by
 * the creatoros.user_memory Postgres table. Selection keeps the original semantics: a memory is
 * injected if it is high-importance (>= 4) OR its tags overlap the current intent's tags.
 */
@Service
public class MemoryInjectionService {

    private static final Logger log = LoggerFactory.getLogger(MemoryInjectionService.class);

    private final NamedParameterJdbcTemplate jdbc;

    public MemoryInjectionService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Build the memory block injected into the reasoning prompt: tag overlap OR high importance. */
    public String buildMemoryBlock(long userId, List<String> intentTags) {
        Set<String> tags = intentTags == null ? Set.of() : Set.copyOf(intentTags);

        List<Map<String, Object>> rows;
        try {
            rows = jdbc.queryForList("""
                    SELECT id, content, importance, tags
                    FROM creatoros.user_memory
                    WHERE user_id = :uid
                    ORDER BY importance DESC NULLS LAST, last_referenced_at DESC NULLS LAST
                    """, new MapSqlParameterSource("uid", userId));
        } catch (Exception e) {
            log.warn("user_memory read failed: {}", e.getMessage());
            return "";
        }

        List<Long> selectedIds = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> row : rows) {
            int importance = row.get("importance") == null ? 0 : ((Number) row.get("importance")).intValue();
            Set<String> rowTags = toStringSet(row.get("tags"));
            boolean relevant = importance >= 4 || !Collections.disjoint(rowTags, tags);
            if (!relevant) continue;

            sb.append("- ").append(row.get("content")).append('\n');
            selectedIds.add(((Number) row.get("id")).longValue());
            if (selectedIds.size() >= 12) break;
        }

        bumpReferenced(selectedIds);
        return sb.toString();
    }

    /** Mark the injected memories as just-referenced so recency ranking stays meaningful. */
    private void bumpReferenced(List<Long> ids) {
        if (ids.isEmpty()) return;
        try {
            jdbc.update("UPDATE creatoros.user_memory SET last_referenced_at = now() WHERE id IN (:ids)",
                    new MapSqlParameterSource("ids", ids));
        } catch (Exception e) {
            log.warn("user_memory last_referenced_at update failed: {}", e.getMessage());
        }
    }

    private static Set<String> toStringSet(Object tagsColumn) {
        try {
            if (tagsColumn instanceof Array sqlArray) {
                Object[] arr = (Object[]) sqlArray.getArray();
                Set<String> out = new java.util.HashSet<>();
                for (Object o : arr) if (o != null) out.add(o.toString());
                return out;
            }
        } catch (Exception ignored) {
            // fall through to empty set
        }
        return Set.of();
    }
}
