package org.example.coral.analytics;

import org.example.coral.coral.CoralExecutionService;
import org.example.coral.coral.SchemaCatalog;
import org.example.coral.coral.TableSpec;
import org.example.coral.model.CoralQueryPlan;
import org.example.coral.model.CoralResultSet;
import org.example.coral.model.TimelineEvent;
import org.example.coral.model.ValidatedQuery;
import org.example.coral.security.SqlValidationService;
import org.example.coral.security.ValidationException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Builds the unified, time-sorted activity stream across every catalog source. Reuses the same
 * validate -> execute -> aggregate path as chat, so timeline reads are subject to identical safety.
 */
@Service
public class TimelineService {

    private final SchemaCatalog catalog;
    private final SqlValidationService validation;
    private final CoralExecutionService coralExecution;
    private final ContextAggregationService aggregation;

    public TimelineService(SchemaCatalog catalog, SqlValidationService validation,
                           CoralExecutionService coralExecution, ContextAggregationService aggregation) {
        this.catalog = catalog;
        this.validation = validation;
        this.coralExecution = coralExecution;
        this.aggregation = aggregation;
    }

    public List<TimelineEvent> buildTimeline() {
        Set<String> all = catalog.all().keySet();
        List<CoralResultSet> results = new ArrayList<>();
        int n = 0;
        for (String table : all) {
            TableSpec spec = catalog.find(table).orElse(null);
            if (spec == null) continue;
            String cols = String.join(", ", spec.columns());
            String sql = "SELECT " + cols + " FROM " + table + " LIMIT 100";
            try {
                ValidatedQuery vq = validation.validate(
                        new CoralQueryPlan.Operation("tl" + (++n), sql, java.util.Map.of()), all);
                results.add(coralExecution.execute(vq));
            } catch (ValidationException ignored) {
                // a source that fails validation is simply omitted from the timeline
            }
        }
        return aggregation.aggregate(results);
    }
}
