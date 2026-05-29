package org.example.coral.ai;

import org.example.coral.coral.SchemaCatalog;
import org.example.coral.coral.TableSpec;
import org.example.coral.model.CoralQueryPlan;
import org.example.coral.model.IntentResult;
import org.example.coral.util.Json;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AI stage 2: produce candidate Coral SQL (never executed here). Falls back to deterministic
 * per-source SELECTs when the model is unavailable, so reads still work offline. The planner is
 * constrained by the injected schema block — it can only reference what the catalog exposes.
 */
@Service
public class QueryPlanningService {

    private final AiGateway ai;
    private final PromptBuilderService prompts;
    private final SchemaCatalog catalog;

    public QueryPlanningService(AiGateway ai, PromptBuilderService prompts, SchemaCatalog catalog) {
        this.ai = ai;
        this.prompts = prompts;
        this.catalog = catalog;
    }

    public CoralQueryPlan plan(IntentResult intent, Set<String> allowedTables) {
        String schemaBlock = catalog.renderSchemaBlock(allowedTables);
        return ai.complete(prompts.planningPrompt(intent, schemaBlock))
                .flatMap(raw -> Json.parse(raw, CoralQueryPlan.class))
                .filter(p -> p.operations() != null && !p.operations().isEmpty())
                .orElseGet(() -> heuristic(intent, allowedTables));
    }

    /** Deterministic fallback: a bounded SELECT of explicit columns per allowed source. */
    CoralQueryPlan heuristic(IntentResult intent, Set<String> allowedTables) {
        if (intent.isMutation()) {
            return mutationHeuristic(intent);
        }
        List<CoralQueryPlan.Operation> ops = new ArrayList<>();
        int n = 0;
        for (String table : allowedTables) {
            TableSpec spec = catalog.find(table).orElse(null);
            if (spec == null) continue;
            String cols = String.join(", ", spec.columns());
            String sql = "SELECT " + cols + " FROM " + table + " LIMIT 50";
            ops.add(new CoralQueryPlan.Operation("q" + (++n), sql, Map.of()));
        }
        return new CoralQueryPlan("fallback per-source read", ops, "post-process", "rows per source");
    }

    /** Best-effort offline mutation plan, keyed by the classified action type. */
    private CoralQueryPlan mutationHeuristic(IntentResult intent) {
        String value = intent.targetValue() != null ? intent.targetValue() : "done";
        String entity = intent.entity() != null ? intent.entity() : "";
        CoralQueryPlan.Operation op = switch (intent.actionType() == null ? "" : intent.actionType()) {
            case "task.update_status" -> new CoralQueryPlan.Operation("m1",
                    "UPDATE notion.tasks SET status = :status WHERE title = :title",
                    Map.of("status", value, "title", entity));
            case "task.update_deadline" -> new CoralQueryPlan.Operation("m1",
                    "UPDATE notion.tasks SET due_date = :due WHERE title = :title",
                    Map.of("due", value, "title", entity));
            case "reminder.archive" -> new CoralQueryPlan.Operation("m1",
                    "UPDATE gmail.emails SET is_archived = true WHERE id = :id",
                    Map.of("id", entity));
            case "reminder.delete_dup" -> new CoralQueryPlan.Operation("m1",
                    "DELETE FROM gmail.emails WHERE id = :id",
                    Map.of("id", entity));
            default -> null;
        };
        List<CoralQueryPlan.Operation> ops = op == null ? List.of() : List.of(op);
        return new CoralQueryPlan("fallback mutation plan", ops, "post-process", "affected rows");
    }
}
