package org.example.coral.security;

import org.example.coral.model.ActionClass;
import org.example.coral.model.IntentResult;
import org.example.coral.model.PolicyDecision;
import org.example.coral.model.ValidatedQuery;
import org.example.coral.model.ValidatedQuery.StatementType;
import org.example.coral.model.Verdict;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PolicyEngineTest {

    private final PolicyEngine engine = new PolicyEngine();

    private IntentResult intent(ActionClass cls, String actionType) {
        return new IntentResult("s", cls, actionType, List.of("notion.tasks"), List.of(), "e", "v");
    }

    private ValidatedQuery query(StatementType type, String table, List<String> writeFields) {
        return new ValidatedQuery("op", type, table, List.of(table), writeFields, "sql", Map.of());
    }

    @Test
    void readsAreAlwaysAllowed() {
        var q = query(StatementType.SELECT, "notion.tasks", List.of());
        assertEquals(Verdict.ALLOW, engine.evaluate(intent(ActionClass.READ, null), q, 0).verdict());
    }

    @Test
    void whitelistedSingleRowUpdateIsAllowed() {
        var q = query(StatementType.UPDATE, "notion.tasks", List.of("status"));
        PolicyDecision d = engine.evaluate(intent(ActionClass.MUTATE, "task.update_status"), q, 1);
        assertEquals(Verdict.ALLOW, d.verdict());
    }

    @Test
    void deadlineUpdateRequiresConfirmation() {
        var q = query(StatementType.UPDATE, "notion.tasks", List.of("due_date"));
        PolicyDecision d = engine.evaluate(intent(ActionClass.MUTATE, "task.update_deadline"), q, 1);
        assertEquals(Verdict.CONFIRM, d.verdict());
    }

    @Test
    void unknownActionIsDenied() {
        var q = query(StatementType.DELETE, "notion.tasks", List.of());
        PolicyDecision d = engine.evaluate(intent(ActionClass.MUTATE, "task.delete_all"), q, 999);
        assertEquals(Verdict.DENY, d.verdict());
    }

    @Test
    void exceedingRowCeilingIsDenied() {
        var q = query(StatementType.UPDATE, "notion.tasks", List.of("status"));
        PolicyDecision d = engine.evaluate(intent(ActionClass.MUTATE, "task.update_status"), q, 5);
        assertEquals(Verdict.DENY, d.verdict());
    }

    @Test
    void nonWhitelistedFieldIsDenied() {
        var q = query(StatementType.UPDATE, "notion.tasks", List.of("due_date"));
        PolicyDecision d = engine.evaluate(intent(ActionClass.MUTATE, "task.update_status"), q, 1);
        assertEquals(Verdict.DENY, d.verdict());
    }

    @Test
    void wrongSchemaForActionIsDenied() {
        var q = query(StatementType.UPDATE, "gmail.emails", List.of("is_archived"));
        PolicyDecision d = engine.evaluate(intent(ActionClass.MUTATE, "task.update_status"), q, 1);
        assertEquals(Verdict.DENY, d.verdict());
    }
}
