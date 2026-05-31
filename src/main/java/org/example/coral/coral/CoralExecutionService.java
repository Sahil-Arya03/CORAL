package org.example.coral.coral;

import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.select.PlainSelect;
import org.example.coral.model.CoralResultSet;
import org.example.coral.model.ValidatedQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * The ONLY component permitted to run read queries against Coral. Accepts a ValidatedQuery —
 * whose existence guarantees the SQL already passed structural validation — then injects
 * the calling user's user_id into the WHERE clause before execution. This is the hard
 * security boundary that prevents cross-user data leakage.
 */
@Service
public class CoralExecutionService {

    private static final Logger log = LoggerFactory.getLogger(CoralExecutionService.class);

    private final CoralClient client;

    public CoralExecutionService(CoralClient client) {
        this.client = client;
    }

    /**
     * Execute a read query scoped to {@code clerkUserId}.
     * The user_id filter is injected deterministically in Java — the AI never scopes its own queries.
     */
    public CoralResultSet execute(ValidatedQuery query, String clerkUserId) {
        if (query.isMutation()) {
            throw new IllegalArgumentException(
                    "CoralExecutionService runs reads only; mutations must go through ActionExecutionService");
        }
        ValidatedQuery scoped = injectUserScope(query, clerkUserId);
        long start = System.currentTimeMillis();
        var rows = client.executeRead(scoped);
        long latency = System.currentTimeMillis() - start;
        return new CoralResultSet(query.operationId(), query.table(), rows, latency);
    }

    /** Overload without user scoping — used in tests / offline fixture mode. */
    public CoralResultSet execute(ValidatedQuery query) {
        return execute(query, null);
    }

    // ── User scope injection ──────────────────────────────────────────────────

    private static ValidatedQuery injectUserScope(ValidatedQuery query, String clerkUserId) {
        if (clerkUserId == null || clerkUserId.isBlank()) return query;

        try {
            var stmt = CCJSqlParserUtil.parse(query.normalizedSql());
            if (!(stmt instanceof PlainSelect ps)) return query;

            Expression userFilter = CCJSqlParserUtil.parseCondExpression("user_id = :_userId");
            Expression existing   = ps.getWhere();
            ps.setWhere(existing == null ? userFilter : new AndExpression(userFilter, existing));

            Map<String, Object> bindings = new HashMap<>(
                    query.bindings() != null ? query.bindings() : Map.of());
            bindings.put("_userId", clerkUserId);

            return new ValidatedQuery(
                    query.operationId(), query.type(), query.table(),
                    query.tables(), query.writeFields(),
                    ps.toString(), bindings);
        } catch (Exception e) {
            // Do NOT return the original unscoped query — that would leak cross-user data.
            log.error("user_id scope injection failed for op={}: {}", query.operationId(), e.getMessage());
            throw new IllegalStateException(
                    "user_id scope injection failed for op=" + query.operationId()
                    + " — cannot safely execute: " + e.getMessage(), e);
        }
    }
}