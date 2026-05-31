package org.example.coral.coral;

import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.expression.JdbcNamedParameter;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.update.Update;
import org.example.coral.model.ValidatedQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Real Coral Runtime adapter: runs validated SQL against the federated Postgres (Neon).
 * Catalog names are dotted (e.g. github.commits) and map straight onto Postgres schema.table,
 * so a ValidatedQuery's normalizedSql executes unchanged.
 *
 * BOUNDARY: package-private on purpose. Reads go through CoralExecutionService, mutations
 * through ActionExecutionService — nothing outside this package may call it directly.
 */
@Component
class CoralClient {

    private static final Logger  log             = LoggerFactory.getLogger(CoralClient.class);
    private static final Pattern NAMED_PARAM_RE  = Pattern.compile(":(\\w+)");

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    CoralClient(JdbcTemplate jdbc, NamedParameterJdbcTemplate named) {
        this.jdbc = jdbc;
        this.named = named;
    }

    /**
     * Execute a read using as much of the AI-planned WHERE clause as the provided bindings support.
     *
     * Three cases:
     *  1. No named params in SQL → run as-is; WHERE is all literals, indexes are usable.
     *  2. All params present in bindings → run with full WHERE via named-param template.
     *  3. Some params missing → prune only the unresolvable conditions (AND branches dropped
     *     individually; OR branches containing any missing param dropped entirely to avoid
     *     widening the result set), then run what remains.
     *
     * Temporal columns are normalized to ISO-8601 strings so the aggregation layer stays correct.
     */
    List<Map<String, Object>> executeRead(ValidatedQuery query) {
        String sql = query.normalizedSql();
        Map<String, Object> bindings = query.bindings() != null ? query.bindings() : Map.of();

        Set<String> required = extractNamedParams(sql);

        String execSql;
        if (required.isEmpty()) {
            execSql = sql;
        } else {
            Set<String> missing = new HashSet<>(required);
            missing.removeAll(bindings.keySet());
            execSql = missing.isEmpty() ? sql : pruneUnresolvableConditions(sql, missing);
        }

        Set<String> remaining = extractNamedParams(execSql);
        List<Map<String, Object>> rows = remaining.isEmpty()
                ? jdbc.queryForList(execSql)
                : named.queryForList(execSql, bindings);

        rows.forEach(CoralClient::normalizeTemporal);
        return rows;
    }

    /** Execute a mutation and return the affected-row count. */
    int executeMutation(ValidatedQuery query) {
        Map<String, Object> bindings = query.bindings();
        return (bindings == null || bindings.isEmpty())
                ? jdbc.update(query.normalizedSql())
                : named.update(query.normalizedSql(), bindings);
    }

    /**
     * Execute an UPDATE or DELETE scoped to the calling user.
     * Injects AND user_id = :_userId into the WHERE clause via JSqlParser before running,
     * so mutations can never affect another user's rows even if the AI omits the filter.
     */
    int executeMutationScoped(ValidatedQuery query, String clerkUserId) {
        if (clerkUserId == null || clerkUserId.isBlank()) {
            return executeMutation(query);
        }
        try {
            Statement stmt = CCJSqlParserUtil.parse(query.normalizedSql());
            net.sf.jsqlparser.expression.Expression userFilter =
                    CCJSqlParserUtil.parseCondExpression("user_id = :_userId");

            if (stmt instanceof net.sf.jsqlparser.statement.update.Update u) {
                net.sf.jsqlparser.expression.Expression existing = u.getWhere();
                u.setWhere(existing == null ? userFilter
                        : new AndExpression(userFilter, existing));
            } else if (stmt instanceof net.sf.jsqlparser.statement.delete.Delete d) {
                net.sf.jsqlparser.expression.Expression existing = d.getWhere();
                d.setWhere(existing == null ? userFilter
                        : new AndExpression(userFilter, existing));
            } else {
                return executeMutation(query);
            }

            Map<String, Object> bindings = new HashMap<>(
                    query.bindings() != null ? query.bindings() : Map.of());
            bindings.put("_userId", clerkUserId);
            return named.update(stmt.toString(), bindings);
        } catch (Exception e) {
            log.warn("mutation user_id scope injection failed: {}", e.getMessage());
            throw new IllegalStateException(
                    "mutation user_id scope injection failed — cannot safely execute: " + e.getMessage(), e);
        }
    }

    /**
     * Execute an INSERT for a new task/row. Injects a generated UUID as id and the calling
     * user's Clerk ID as user_id. The normalizedSql from the AI is modified in-place by
     * prepending id, user_id, and updated_at to the column list and their values accordingly.
     *
     * Uses string manipulation rather than JSqlParser re-serialization to avoid quote-stripping
     * issues and to preserve the AI's original CAST / function expressions in VALUES.
     */
    int executeInsert(ValidatedQuery query, String clerkUserId) {
        String sql = query.normalizedSql().trim();

        // Locate the column list: first parenthesised group
        int colsOpen  = sql.indexOf('(');
        int colsClose = findMatchingParen(sql, colsOpen);

        // Locate the VALUES list: parenthesised group after the VALUES keyword
        int valuesIdx = sql.toUpperCase(java.util.Locale.ROOT).indexOf(" VALUES ");
        if (colsOpen < 0 || valuesIdx < 0) {
            throw new IllegalArgumentException("Cannot parse INSERT SQL for scope injection: " + sql);
        }
        int valsOpen  = sql.indexOf('(', valuesIdx);
        int valsClose = findMatchingParen(sql, valsOpen);

        String prefix  = sql.substring(0, colsOpen + 1);       // "INSERT INTO notion.tasks ("
        String cols    = sql.substring(colsOpen  + 1, colsClose);  // existing column list
        String middle  = sql.substring(colsClose + 1, valsOpen + 1); // ") VALUES ("
        String vals    = sql.substring(valsOpen  + 1, valsClose);    // existing value list

        String uuid = java.util.UUID.randomUUID().toString();

        String newSql = prefix + "id, user_id, updated_at, " + cols + ")"
                + middle
                + ":_id, :_userId, CAST(:_now AS timestamptz), " + vals + ")";

        Map<String, Object> bindings = new HashMap<>(
                query.bindings() != null ? query.bindings() : Map.of());
        bindings.put("_id",     uuid);
        bindings.put("_userId", clerkUserId);
        bindings.put("_now",    Instant.now().toString());

        return named.update(newSql, bindings);
    }

    /**
     * Non-mutating row estimate for the policy row-ceiling check: rewrites the UPDATE/DELETE
     * to SELECT COUNT(*) over the same table + WHERE, so nothing is written before confirmation.
     */
    int estimateMutation(ValidatedQuery query) {
        try {
            Statement stmt = CCJSqlParserUtil.parse(query.normalizedSql());
            String table;
            String where;
            if (stmt instanceof Update u) {
                table = u.getTable().getFullyQualifiedName();
                where = u.getWhere() == null ? null : u.getWhere().toString();
            } else if (stmt instanceof Delete d) {
                table = d.getTable().getFullyQualifiedName();
                where = d.getWhere() == null ? null : d.getWhere().toString();
            } else {
                return 0;
            }
            String countSql = "SELECT COUNT(*) FROM " + table + (where == null ? "" : " WHERE " + where);
            Map<String, Object> bindings = query.bindings();
            Integer n = (bindings == null || bindings.isEmpty())
                    ? jdbc.queryForObject(countSql, Integer.class)
                    : named.queryForObject(countSql, bindings, Integer.class);
            return n == null ? 0 : n;
        } catch (Exception e) {
            return 1;
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Returns the index of the closing ')' that matches the '(' at {@code openPos}. */
    private static int findMatchingParen(String sql, int openPos) {
        int depth = 0;
        for (int i = openPos; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') { if (--depth == 0) return i; }
        }
        return sql.length() - 1;
    }

    // ── WHERE pruning ────────────────────────────────────────────────────────

    /**
     * Parse the SELECT's WHERE, drop any conditions that reference a param from {@code missing},
     * and return the rewritten SQL. Falls back to a full WHERE-strip on any parse failure.
     */
    private static String pruneUnresolvableConditions(String sql, Set<String> missing) {
        try {
            Statement stmt = CCJSqlParserUtil.parse(sql);
            if (!(stmt instanceof PlainSelect ps)) return stripWhere(sql);
            ps.setWhere(pruneExpr(ps.getWhere(), missing));
            return ps.toString();
        } catch (Exception e) {
            return stripWhere(sql);
        }
    }

    /**
     * Recursively prune expression nodes that reference any param in {@code missing}.
     * AND children are pruned individually so unaffected conditions survive.
     * OR nodes are dropped whole — removing one OR branch would widen the result set.
     */
    private static Expression pruneExpr(Expression expr, Set<String> missing) {
        if (expr == null || !containsMissingParam(expr, missing)) return expr;

        if (expr instanceof AndExpression and) {
            Expression left  = pruneExpr(and.getLeftExpression(),  missing);
            Expression right = pruneExpr(and.getRightExpression(), missing);
            if (left == null)  return right;
            if (right == null) return left;
            return new AndExpression(left, right);
        }

        return null; // leaf condition or OR with a missing param → drop
    }

    private static boolean containsMissingParam(Expression expr, Set<String> missing) {
        boolean[] found = {false};
        expr.accept(new ExpressionVisitorAdapter() {
            @Override
            public void visit(JdbcNamedParameter p) {
                if (missing.contains(p.getName())) found[0] = true;
            }
        });
        return found[0];
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static Set<String> extractNamedParams(String sql) {
        Set<String> params = new HashSet<>();
        Matcher m = NAMED_PARAM_RE.matcher(sql);
        while (m.find()) params.add(m.group(1));
        return params;
    }

    private static String stripWhere(String sql) {
        try {
            Statement stmt = CCJSqlParserUtil.parse(sql);
            if (stmt instanceof PlainSelect ps) {
                ps.setWhere(null);
                return ps.toString();
            }
        } catch (Exception ignored) {}
        return sql;
    }

    private static void normalizeTemporal(Map<String, Object> row) {
        for (Map.Entry<String, Object> e : row.entrySet()) {
            Object v = e.getValue();
            if (v instanceof Timestamp ts)            e.setValue(ts.toInstant().toString());
            else if (v instanceof OffsetDateTime odt) e.setValue(odt.toInstant().toString());
            else if (v instanceof Instant ins)        e.setValue(ins.toString());
            else if (v instanceof Date d)             e.setValue(d.toInstant().toString());
        }
    }
}
