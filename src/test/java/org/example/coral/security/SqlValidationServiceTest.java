package org.example.coral.security;

import org.example.coral.coral.SchemaCatalog;
import org.example.coral.model.CoralQueryPlan;
import org.example.coral.model.ValidatedQuery;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlValidationServiceTest {

    private final SqlValidationService validator = new SqlValidationService(new SchemaCatalog());

    private CoralQueryPlan.Operation op(String sql) {
        return new CoralQueryPlan.Operation("op1", sql, Map.of());
    }

    private ValidatedQuery validate(String sql) {
        return validator.validate(op(sql), Set.of());
    }

    private void expectReject(String sql) {
        assertThrows(ValidationException.class, () -> validate(sql));
    }

    // ---- accepted ----

    @Test
    void acceptsExplicitSelectAndInjectsLimit() {
        ValidatedQuery q = validate("SELECT id, title FROM notion.tasks WHERE status = 'todo'");
        assertEquals(ValidatedQuery.StatementType.SELECT, q.type());
        assertEquals("notion.tasks", q.table());
        assertTrue(q.normalizedSql().toUpperCase().contains("LIMIT 200"),
                "default LIMIT should be injected: " + q.normalizedSql());
    }

    @Test
    void respectsExistingLimit() {
        ValidatedQuery q = validate("SELECT id FROM notion.tasks WHERE status = 'todo' LIMIT 5");
        long limits = q.normalizedSql().toUpperCase().split("LIMIT").length - 1;
        assertEquals(1, limits, "should not double-inject LIMIT");
    }

    @Test
    void acceptsWhitelistedUpdateAndCapturesWriteFields() {
        ValidatedQuery q = validate("UPDATE notion.tasks SET status = 'done' WHERE id = 't1'");
        assertEquals(ValidatedQuery.StatementType.UPDATE, q.type());
        assertEquals(java.util.List.of("status"), q.writeFields());
    }

    @Test
    void acceptsBoundedDelete() {
        ValidatedQuery q = validate("DELETE FROM gmail.emails WHERE id = 'm2'");
        assertEquals(ValidatedQuery.StatementType.DELETE, q.type());
    }

    // ---- rejected: statement type ----

    @Test void rejectsDrop()     { expectReject("DROP TABLE notion.tasks"); }
    @Test void rejectsAlter()    { expectReject("ALTER TABLE notion.tasks ADD COLUMN x int"); }
    @Test void rejectsTruncate() { expectReject("TRUNCATE TABLE notion.tasks"); }
    @Test void rejectsCreate()   { expectReject("CREATE TABLE evil (id int)"); }

    // ---- rejected: structural ----

    @Test
    void rejectsStackedStatements() {
        expectReject("SELECT id FROM notion.tasks WHERE id='t1'; DROP TABLE notion.tasks");
    }

    @Test void rejectsSelectStar() { expectReject("SELECT * FROM notion.tasks"); }

    @Test void rejectsUnknownTable() {
        expectReject("SELECT id FROM notion.secrets WHERE id = 'x'");
    }

    @Test void rejectsUnknownColumn() {
        expectReject("SELECT password FROM notion.tasks WHERE id = 't1'");
    }

    // ---- rejected: mutation safety ----

    @Test void rejectsUpdateWithoutWhere() {
        expectReject("UPDATE notion.tasks SET status = 'done'");
    }

    @Test void rejectsTautologicalUpdate() {
        expectReject("UPDATE notion.tasks SET status = 'done' WHERE 1 = 1");
    }

    @Test void rejectsDeleteWithoutWhere() {
        expectReject("DELETE FROM gmail.emails");
    }

    @Test void rejectsUpdateOnNonMutableField() {
        expectReject("UPDATE notion.tasks SET title = 'x' WHERE id = 't1'");
    }

    @Test void rejectsUpdateOnReadOnlyTable() {
        expectReject("UPDATE github.commits SET message = 'x' WHERE sha = 'a1b2'");
    }
}
