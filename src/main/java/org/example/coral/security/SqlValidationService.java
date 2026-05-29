package org.example.coral.security;

import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.statement.update.UpdateSet;
import org.example.coral.coral.SchemaCatalog;
import org.example.coral.coral.TableSpec;
import org.example.coral.model.CoralQueryPlan;
import org.example.coral.model.ValidatedQuery;
import org.example.coral.model.ValidatedQuery.StatementType;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Deterministic, reject-by-default SQL gate. Parses each candidate query to a real AST
 * (JSqlParser) and applies layered structural checks. Anything not provably safe is denied.
 * The existence of a returned ValidatedQuery is the safety guarantee downstream relies on.
 */
@Service
public class SqlValidationService {

    private static final int DEFAULT_LIMIT = 200;

    private final SchemaCatalog catalog;

    public SqlValidationService(SchemaCatalog catalog) {
        this.catalog = catalog;
    }

    public ValidatedQuery validate(CoralQueryPlan.Operation op, Set<String> allowedTables) {
        String sql = op.sql() == null ? "" : op.sql().trim();
        if (sql.isEmpty()) {
            throw new ValidationException("Empty query");
        }

        // 1. PARSE — must be exactly one statement (reject stacked / ';' chaining).
        Statement stmt = parseSingle(sql);

        // 2. STATEMENT TYPE — SELECT | UPDATE | DELETE only.
        StatementType type = statementType(stmt);

        // 3. TABLE CHECK — every referenced table in catalog AND allowed for this intent.
        List<String> tables = referencedTables(stmt);
        if (tables.isEmpty()) {
            throw new ValidationException("No table referenced");
        }
        for (String t : tables) {
            if (!catalog.contains(t)) {
                throw new ValidationException("Unknown table not in catalog: " + t);
            }
            if (allowedTables != null && !allowedTables.isEmpty() && !allowedTables.contains(t)) {
                throw new ValidationException("Table not permitted for this intent: " + t);
            }
        }

        String primaryTable = primaryTable(stmt, tables);
        TableSpec spec = catalog.find(primaryTable)
                .orElseThrow(() -> new ValidationException("Unknown primary table: " + primaryTable));

        // 5. OPERATION CHECK — op type allowed on this table.
        if (!spec.allows(type)) {
            throw new ValidationException(type + " not permitted on " + primaryTable);
        }

        // Collect known columns across all referenced tables for column validation.
        Set<String> knownColumns = new HashSet<>();
        for (String t : tables) {
            catalog.find(t).ifPresent(s -> knownColumns.addAll(s.columns()));
        }

        List<String> writeFields = new ArrayList<>();
        switch (type) {
            case SELECT -> validateSelect((Select) stmt, knownColumns);
            case UPDATE -> writeFields.addAll(validateUpdate((Update) stmt, spec, knownColumns));
            case DELETE -> validateDelete((Delete) stmt, knownColumns);
        }

        // 8. SHAPE LIMITS — ensure a LIMIT on SELECT.
        String normalized = normalize(stmt, type);

        return new ValidatedQuery(op.id(), type, primaryTable, tables, writeFields, normalized, op.bindings());
    }

    private Statement parseSingle(String sql) {
        try {
            Statements statements = CCJSqlParserUtil.parseStatements(sql);
            List<Statement> list = statements.getStatements();
            if (list.size() != 1) {
                throw new ValidationException("Exactly one statement allowed; found " + list.size());
            }
            return list.get(0);
        } catch (ValidationException ve) {
            throw ve;
        } catch (Exception e) {
            // Unparseable, or a statement type JSqlParser flags — treat as unsafe.
            throw new ValidationException("Unparseable or unsupported SQL: " + e.getMessage());
        }
    }

    private StatementType statementType(Statement stmt) {
        if (stmt instanceof Select) return StatementType.SELECT;
        if (stmt instanceof Update) return StatementType.UPDATE;
        if (stmt instanceof Delete) return StatementType.DELETE;
        // DROP, ALTER, TRUNCATE, CREATE, GRANT, etc. all land here.
        throw new ValidationException("Statement type not allowed: " + stmt.getClass().getSimpleName());
    }

    private List<String> referencedTables(Statement stmt) {
        List<String> names = new ArrayList<>();
        if (stmt instanceof Select select && select instanceof PlainSelect ps) {
            if (ps.getFromItem() instanceof net.sf.jsqlparser.schema.Table table) {
                names.add(stripQuotes(table.getFullyQualifiedName()));
            }
            if (ps.getJoins() != null) {
                ps.getJoins().forEach(j -> {
                    if (j.getRightItem() instanceof net.sf.jsqlparser.schema.Table jt) {
                        names.add(stripQuotes(jt.getFullyQualifiedName()));
                    }
                });
            }
        } else if (stmt instanceof Update update) {
            names.add(stripQuotes(update.getTable().getFullyQualifiedName()));
        } else if (stmt instanceof Delete delete) {
            names.add(stripQuotes(delete.getTable().getFullyQualifiedName()));
        }
        return names;
    }

    private String primaryTable(Statement stmt, List<String> tables) {
        return tables.get(0);
    }

    private void validateSelect(Select select, Set<String> knownColumns) {
        if (!(select instanceof PlainSelect ps)) {
            throw new ValidationException("Only plain SELECT statements are allowed");
        }
        for (SelectItem<?> item : ps.getSelectItems()) {
            var expr = item.getExpression();
            if (expr instanceof AllColumns) {
                throw new ValidationException("SELECT * is not allowed; list explicit columns");
            }
            if (expr instanceof Column col) {
                requireKnownColumn(col.getColumnName(), knownColumns);
            }
            // Functions / aggregates / aliases on known columns are permitted as-is.
        }
        // Validate WHERE columns if present (no tautology requirement for reads).
        if (ps.getWhere() != null) {
            collectColumns(ps.getWhere()).forEach(c -> requireKnownColumn(c, knownColumns));
        }
    }

    private List<String> validateUpdate(Update update, TableSpec spec, Set<String> knownColumns) {
        // 7. FIELD CHECK — SET targets must be mutable on this table.
        List<UpdateSet> sets = update.getUpdateSets();
        if (sets == null || sets.isEmpty()) {
            throw new ValidationException("UPDATE without SET clause");
        }
        List<String> writeFields = new ArrayList<>();
        for (UpdateSet set : sets) {
            for (Column col : set.getColumns()) {
                String name = col.getColumnName();
                requireKnownColumn(name, knownColumns);
                if (!spec.isMutable(name)) {
                    throw new ValidationException("Field not updatable: " + spec.name() + "." + name);
                }
                writeFields.add(name);
            }
        }
        // 6. MUTATION SAFETY — bounded WHERE required.
        requireBoundedWhere(update.getWhere(), knownColumns, "UPDATE");
        return writeFields;
    }

    private void validateDelete(Delete delete, Set<String> knownColumns) {
        requireBoundedWhere(delete.getWhere(), knownColumns, "DELETE");
    }

    /**
     * A mutation's WHERE must exist and reference at least one real column. This rejects
     * WHERE-less mutations and tautologies (WHERE 1=1, WHERE TRUE) which contain no column.
     */
    private void requireBoundedWhere(net.sf.jsqlparser.expression.Expression where,
                                     Set<String> knownColumns, String op) {
        if (where == null) {
            throw new ValidationException(op + " without WHERE is not allowed");
        }
        List<String> cols = collectColumns(where);
        if (cols.isEmpty()) {
            throw new ValidationException(op + " with an unbounded/tautological WHERE is not allowed");
        }
        cols.forEach(c -> requireKnownColumn(c, knownColumns));
    }

    private void requireKnownColumn(String column, Set<String> knownColumns) {
        if (!knownColumns.contains(column)) {
            throw new ValidationException("Unknown column not in catalog: " + column);
        }
    }

    private List<String> collectColumns(net.sf.jsqlparser.expression.Expression expr) {
        List<String> cols = new ArrayList<>();
        expr.accept(new ExpressionVisitorAdapter() {
            @Override
            public void visit(Column column) {
                cols.add(column.getColumnName());
            }
        });
        return cols;
    }

    private String normalize(Statement stmt, StatementType type) {
        String sql = stmt.toString();
        if (type == StatementType.SELECT && stmt instanceof PlainSelect ps && ps.getLimit() == null) {
            sql = sql + " LIMIT " + DEFAULT_LIMIT;
        }
        return sql;
    }

    private String stripQuotes(String name) {
        return name == null ? null : name.replace("\"", "").replace("`", "");
    }
}
