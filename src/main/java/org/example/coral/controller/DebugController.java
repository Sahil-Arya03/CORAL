package org.example.coral.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.example.coral.ai.IntentExtractionService;
import org.example.coral.ai.QueryPlanningService;
import org.example.coral.config.SecurityUtils;
import org.example.coral.coral.CoralExecutionService;
import org.example.coral.model.ActionClass;
import org.example.coral.model.CoralQueryPlan;
import org.example.coral.model.CoralResultSet;
import org.example.coral.model.IntentResult;
import org.example.coral.security.SqlValidationService;
import org.example.coral.security.ValidationException;
import org.example.coral.service.ActionClassificationService;
import org.example.coral.service.SchemaContextService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * TEMPORARY diagnostic endpoint — remove after confirming the pipeline fix.
 *
 * GET /api/debug/pipeline?q=what+are+my+tasks
 *
 * Runs the AI pipeline stages (intent → planning → validation → execution) and returns
 * a JSON summary of what each stage produced, without writing any conversation history
 * or side-effects. Use this to verify that:
 *   1. ActionClass is READ (not SMALLTALK)
 *   2. SQL is generated and passes validation
 *   3. Rows are returned by CoralExecutionService
 *   4. user_id is NOT in the AI-generated WHERE clause
 */
@RestController
@RequestMapping("/api/debug")
public class DebugController {

    private static final Logger log = LoggerFactory.getLogger(DebugController.class);

    private final IntentExtractionService  intentExtraction;
    private final ActionClassificationService classification;
    private final SchemaContextService     schemaContext;
    private final QueryPlanningService     planning;
    private final SqlValidationService     validation;
    private final CoralExecutionService    coralExecution;

    public DebugController(IntentExtractionService intentExtraction,
                           ActionClassificationService classification,
                           SchemaContextService schemaContext,
                           QueryPlanningService planning,
                           SqlValidationService validation,
                           CoralExecutionService coralExecution) {
        this.intentExtraction = intentExtraction;
        this.classification   = classification;
        this.schemaContext    = schemaContext;
        this.planning         = planning;
        this.validation       = validation;
        this.coralExecution   = coralExecution;
    }

    @GetMapping("/pipeline")
    public Map<String, Object> diagnose(
            @RequestParam(defaultValue = "what are my tasks?") String q,
            HttpServletRequest request) {

        String clerkUserId = resolveClerkId(request);
        Map<String, Object> diag = new LinkedHashMap<>();
        diag.put("query",       q);
        diag.put("clerkUserId", clerkUserId);

        // Stage 1-2: intent + routing
        IntentResult intent = intentExtraction.extract(q);
        ActionClass  route  = classification.classify(intent);
        diag.put("actionClass",    route.name());
        diag.put("intentSummary",  intent.summary());
        diag.put("intentSources",  intent.sources());
        diag.put("intentActionType", intent.actionType());

        if (route == ActionClass.SMALLTALK) {
            diag.put("shortCircuit", "SMALLTALK — no DB read, pipeline stops here");
            return diag;
        }

        // Stage 3-4: schema + planning
        Set<String> allowed = schemaContext.resolveAllowedTables(intent);
        diag.put("allowedTables", allowed);

        CoralQueryPlan plan = planning.plan(intent, allowed);
        diag.put("planRationale",     plan.rationale());
        diag.put("operationCount",    plan.operations().size());

        // Stage 5-6: validate + execute each operation
        List<Map<String, Object>> opDiag = new ArrayList<>();
        List<CoralResultSet> results = new ArrayList<>();

        for (CoralQueryPlan.Operation op : plan.operations()) {
            Map<String, Object> opInfo = new LinkedHashMap<>();
            opInfo.put("id",       op.id());
            opInfo.put("sql",      op.sql());
            opInfo.put("bindings", op.bindings());

            boolean hasUserIdInWhere = op.sql() != null
                    && op.sql().toLowerCase().contains("user_id");
            opInfo.put("containsUserIdRef", hasUserIdInWhere);
            if (hasUserIdInWhere) {
                opInfo.put("warning", "SQL references user_id — this will conflict with backend scoping and return 0 rows!");
            }

            try {
                var vq = validation.validate(op, allowed);
                opInfo.put("valid", true);
                try {
                    CoralResultSet rs = coralExecution.execute(vq, clerkUserId);
                    opInfo.put("rows",      rs.size());
                    opInfo.put("latencyMs", rs.latencyMs());
                    results.add(rs);
                } catch (Exception ex) {
                    opInfo.put("executionError", ex.getMessage());
                    opInfo.put("rows", 0);
                }
            } catch (ValidationException ve) {
                opInfo.put("valid",           false);
                opInfo.put("validationError", ve.getMessage());
                opInfo.put("rows",            0);
            }
            opDiag.add(opInfo);
        }

        diag.put("operations",      opDiag);
        diag.put("totalRowsFetched", results.stream().mapToInt(CoralResultSet::size).sum());

        log.info("[debug] pipeline diagnosis for q='{}' uid={}: actionClass={} ops={} totalRows={}",
                q, clerkUserId, route, plan.operations().size(),
                results.stream().mapToInt(CoralResultSet::size).sum());

        return diag;
    }

    private String resolveClerkId(HttpServletRequest req) {
        try { return SecurityUtils.getClerkUserId(req); } catch (Exception e) { return "dev_user"; }
    }
}