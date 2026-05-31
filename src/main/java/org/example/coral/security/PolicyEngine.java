package org.example.coral.security;

import org.example.coral.model.IntentResult;
import org.example.coral.model.PolicyDecision;
import org.example.coral.model.ValidatedQuery;
import org.example.coral.model.ValidatedQuery.StatementType;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic, whitelist-driven authorization. Where validation answers "is this SQL
 * structurally safe?", the policy engine answers "is this user allowed to do this action,
 * now, to these rows?". Reads are always allowed (already constrained by schema allowlist);
 * mutations must match an explicit action rule and pass the row ceiling.
 *
 * Rules are declared in code here for hackathon speed; the shape mirrors a policy.yml so it
 * can be externalized to a ConfigurationProperties bean later without touching the engine.
 */
@Service
public class PolicyEngine {

    private record Rule(String schema, StatementType op, Set<String> fields, int maxRows, boolean confirm) {}

    private final Map<String, Rule> actions = new LinkedHashMap<>();

    public PolicyEngine() {
        // ── notion.tasks ──────────────────────────────────────────────────────────
        actions.put("task.create",
                new Rule("notion.tasks", StatementType.INSERT,
                         Set.of("title", "status", "priority", "due_date", "project", "updated_at"),
                         1, false));
        actions.put("task.update_status",
                new Rule("notion.tasks", StatementType.UPDATE, Set.of("status"),   1, false));
        actions.put("task.update_deadline",
                new Rule("notion.tasks", StatementType.UPDATE, Set.of("due_date"), 1, true));
        actions.put("task.update_priority",
                new Rule("notion.tasks", StatementType.UPDATE, Set.of("priority"), 1, false));
        actions.put("task.update_title",
                new Rule("notion.tasks", StatementType.UPDATE, Set.of("title"),    1, false));
        actions.put("task.update_project",
                new Rule("notion.tasks", StatementType.UPDATE, Set.of("project"),  1, false));
        actions.put("task.delete",
                new Rule("notion.tasks", StatementType.DELETE, Set.of(),           1, true));

        // ── gmail.emails ──────────────────────────────────────────────────────────
        actions.put("reminder.archive",
                new Rule("gmail.emails", StatementType.UPDATE, Set.of("is_archived"), 10, false));
        actions.put("reminder.unarchive",
                new Rule("gmail.emails", StatementType.UPDATE, Set.of("is_archived"), 10, false));
        actions.put("reminder.mark_read",
                new Rule("gmail.emails", StatementType.UPDATE, Set.of("is_unread"),   10, false));
        actions.put("reminder.delete_dup",
                new Rule("gmail.emails", StatementType.DELETE, Set.of(),               5, true));
    }

    /** The closed set of whitelisted action types — the single source of truth for the planner. */
    public Set<String> knownActions() {
        return actions.keySet();
    }

    public PolicyDecision evaluate(IntentResult intent, ValidatedQuery query, int estimatedRows) {
        // Reads are governed by validation + the schema allowlist; nothing further to authorize.
        if (!query.isMutation()) {
            return PolicyDecision.allow("read");
        }

        String actionType = intent.actionType();
        if (actionType == null || !actions.containsKey(actionType)) {
            return PolicyDecision.deny("No policy whitelists action: " + actionType);
        }
        Rule rule = actions.get(actionType);

        if (!rule.schema().equals(query.table())) {
            return PolicyDecision.deny(
                    "Action " + actionType + " not permitted on " + query.table());
        }
        if (rule.op() != query.type()) {
            return PolicyDecision.deny(
                    "Action " + actionType + " expects " + rule.op() + " but query is " + query.type());
        }
        // For INSERT/UPDATE: every inserted/updated column must be whitelisted.
        // For DELETE: writeFields is empty — only the row-ceiling check applies.
        if (!query.writeFields().isEmpty()) {
            for (String field : query.writeFields()) {
                if (!rule.fields().contains(field)) {
                    return PolicyDecision.deny("Field not whitelisted for " + actionType + ": " + field);
                }
            }
        }
        // Destructive-by-scale guard: deny anything exceeding the action's row ceiling.
        if (estimatedRows > rule.maxRows()) {
            return PolicyDecision.deny(
                    "Affected rows (" + estimatedRows + ") exceed ceiling " + rule.maxRows()
                            + " for " + actionType);
        }

        return rule.confirm()
                ? PolicyDecision.confirm("Action " + actionType + " requires confirmation")
                : PolicyDecision.allow(actionType);
    }
}
