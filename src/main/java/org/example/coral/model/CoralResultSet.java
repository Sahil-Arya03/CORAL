package org.example.coral.model;

import java.util.List;
import java.util.Map;

/**
 * Result of executing one validated query against Coral. Rows are schema-agnostic maps
 * so the aggregation layer can normalize them into TimelineEvents.
 */
public record CoralResultSet(
        String operationId,
        String table,
        List<Map<String, Object>> rows,
        long latencyMs
) {
    public int size() {
        return rows.size();
    }
}
