package org.example.coral.model;

import java.util.List;
import java.util.Map;

/**
 * Structured output of the QueryPlanningService (AI stage 2).
 * The AI returns candidate SQL with named bindings — never executed until validated.
 */
public record CoralQueryPlan(
        String rationale,
        List<Operation> operations,
        String joinStrategy,      // "post-process" — cross-source stitching done in Java
        String expectedShape
) {
    public record Operation(
            String id,
            String sql,
            Map<String, Object> bindings
    ) {}

    public static CoralQueryPlan empty(String rationale) {
        return new CoralQueryPlan(rationale, List.of(), "post-process", "none");
    }
}
