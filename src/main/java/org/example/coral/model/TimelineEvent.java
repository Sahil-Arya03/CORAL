package org.example.coral.model;

import java.time.Instant;
import java.util.Map;

/**
 * Normalized cross-source activity unit produced by ContextAggregationService.
 */
public record TimelineEvent(
        String source,      // github | notion | calendar | gmail | reflection
        String type,        // commit | task | event | email | reflection
        Instant occurredAt,
        String title,
        String summary,
        Map<String, Object> meta
) {}
