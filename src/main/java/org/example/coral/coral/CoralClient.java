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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Date;
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

    private static final Pattern NAMED_PARAM_RE = Pattern.compile(":(\\w+)");

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final boolean fixtureMode;

    CoralClient(JdbcTemplate jdbc, NamedParameterJdbcTemplate named) {
        this.jdbc = jdbc;
        this.named = named;
        this.fixtureMode = false;
    }

    /** Offline constructor for unit tests: returns seed-compatible in-memory fixture rows. */
    CoralClient() {
        this.jdbc = null;
        this.named = null;
        this.fixtureMode = true;
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
        if (fixtureMode) return fixtureRows(query.table());

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
        if (fixtureMode) return 1;

        Map<String, Object> bindings = query.bindings();
        return (bindings == null || bindings.isEmpty())
                ? jdbc.update(query.normalizedSql())
                : named.update(query.normalizedSql(), bindings);
    }

    /**
     * Non-mutating row estimate for the policy row-ceiling check: rewrites the UPDATE/DELETE
     * to SELECT COUNT(*) over the same table + WHERE, so nothing is written before confirmation.
     */
    int estimateMutation(ValidatedQuery query) {
        if (fixtureMode) return 1;

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
            if (v instanceof Timestamp ts) {
                e.setValue(ts.toInstant().toString());
            } else if (v instanceof OffsetDateTime odt) {
                e.setValue(odt.toInstant().toString());
            } else if (v instanceof Instant ins) {
                e.setValue(ins.toString());
            } else if (v instanceof Date d) {
                e.setValue(d.toInstant().toString());
            }
        }
    }

    // ── Fixture data (offline / unit-test mode only) ─────────────────────────

    private static List<Map<String, Object>> fixtureRows(String table) {
        Instant now = Instant.now();
        return switch (table) {
            case "notion.tasks" -> List.of(
                Map.of("id", "t1", "title", "Finish DBMS assignment",
                       "status", "todo", "priority", "high",
                       "due_date", now.minus(1, ChronoUnit.DAYS).toString(),
                       "project", "DBMS", "updated_at", now.minus(3, ChronoUnit.DAYS).toString()),
                Map.of("id", "t2", "title", "OS lab report",
                       "status", "in_progress", "priority", "medium",
                       "due_date", now.plus(2, ChronoUnit.DAYS).toString(),
                       "project", "OS", "updated_at", now.minus(1, ChronoUnit.DAYS).toString()),
                Map.of("id", "t3", "title", "DSA practice set",
                       "status", "todo", "priority", "high",
                       "due_date", now.plus(1, ChronoUnit.DAYS).toString(),
                       "project", "DSA", "updated_at", now.minus(5, ChronoUnit.DAYS).toString())
            );
            case "gmail.emails" -> List.of(
                Map.of("id", "m1", "subject", "DBMS assignment deadline moved to tomorrow",
                       "sender", "prof@univ.edu", "snippet", "Please submit by EOD tomorrow.",
                       "is_unread", true, "received_at", now.minus(4, ChronoUnit.HOURS).toString(),
                       "importance", "high", "is_archived", false),
                Map.of("id", "m2", "subject", "Weekly newsletter",
                       "sender", "news@list.com", "snippet", "Top stories this week.",
                       "is_unread", true, "received_at", now.minus(1, ChronoUnit.DAYS).toString(),
                       "importance", "low", "is_archived", false)
            );
            case "github.commits" -> List.of(
                Map.of("sha", "a1b2", "repo", "os-lab", "author", "arya",
                       "message", "wip: scheduler",
                       "committed_at", now.minus(2, ChronoUnit.DAYS).toString()),
                Map.of("sha", "c3d4", "repo", "dsa", "author", "arya",
                       "message", "add two-pointer solutions",
                       "committed_at", now.minus(6, ChronoUnit.HOURS).toString())
            );
            case "github.pull_requests" -> List.of(
                Map.of("id", "pr1", "repo", "os-lab", "title", "Scheduler v1",
                       "state", "open", "created_at", now.minus(3, ChronoUnit.DAYS).toString(),
                       "merged_at", "")
            );
            case "calendar.events" -> List.of(
                Map.of("id", "e1", "title", "DBMS lecture",
                       "start_at", now.plus(4, ChronoUnit.HOURS).toString(),
                       "end_at", now.plus(5, ChronoUnit.HOURS).toString(),
                       "attendees_count", 40, "is_meeting", true),
                Map.of("id", "e2", "title", "Project sync",
                       "start_at", now.plus(1, ChronoUnit.DAYS).toString(),
                       "end_at", now.plus(1, ChronoUnit.DAYS).plus(1, ChronoUnit.HOURS).toString(),
                       "attendees_count", 5, "is_meeting", true)
            );
            default -> List.of();
        };
    }
}
