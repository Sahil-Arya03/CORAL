package org.example.coral.coral;

import org.example.coral.model.ValidatedQuery.StatementType;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.example.coral.model.ValidatedQuery.StatementType.DELETE;
import static org.example.coral.model.ValidatedQuery.StatementType.INSERT;
import static org.example.coral.model.ValidatedQuery.StatementType.SELECT;
import static org.example.coral.model.ValidatedQuery.StatementType.UPDATE;

/**
 * The single curated source of truth for every Coral-exposed table. The AI only ever learns
 * a table or column exists because it appears here; validation rejects anything not present.
 */
@Component
public class SchemaCatalog {

    private final Map<String, TableSpec> tables = new LinkedHashMap<>();

    public SchemaCatalog() {
        register(new TableSpec("github.commits",
                Set.of("sha", "repo", "author", "message", "committed_at", "user_id"),
                Set.of(SELECT), Set.of()));

        register(new TableSpec("github.pull_requests",
                Set.of("id", "repo", "title", "state", "created_at", "merged_at", "user_id"),
                Set.of(SELECT), Set.of()));

        register(new TableSpec("notion.tasks",
                Set.of("id", "title", "status", "priority", "due_date", "project", "updated_at", "user_id"),
                Set.of(SELECT, INSERT, UPDATE, DELETE),
                Set.of("title", "status", "priority", "due_date", "project")));

        register(new TableSpec("calendar.events",
                Set.of("id", "title", "start_at", "end_at", "attendees_count", "is_meeting",
                        "description", "location", "user_id"),
                Set.of(SELECT), Set.of()));

        register(new TableSpec("gmail.emails",
                Set.of("id", "subject", "sender", "snippet", "is_unread", "received_at",
                        "importance", "is_archived", "user_id"),
                Set.of(SELECT, UPDATE, DELETE),
                Set.of("is_archived", "is_unread", "importance")));
    }

    private void register(TableSpec spec) {
        tables.put(spec.name(), spec);
    }

    public boolean contains(String table) {
        return tables.containsKey(table);
    }

    public Optional<TableSpec> find(String table) {
        return Optional.ofNullable(tables.get(table));
    }

    public Map<String, TableSpec> all() {
        return Map.copyOf(tables);
    }

    /**
     * Human-readable schema block injected into AI planning prompts.
     * user_id is intentionally excluded — it is a backend scoping column that the AI must
     * never reference. The backend injects AND user_id = :_userId automatically after
     * validation; if the AI also filters by user_id the resulting double-filter causes zero
     * rows to be returned because the AI doesn't know the real Clerk user ID.
     */
    public String renderSchemaBlock(Set<String> tableNames) {
        StringBuilder sb = new StringBuilder();
        for (String name : tableNames) {
            TableSpec spec = tables.get(name);
            if (spec == null) continue;
            String cols = spec.columns().stream()
                    .filter(c -> !c.equals("user_id"))
                    .collect(java.util.stream.Collectors.joining(", "));
            sb.append(name)
              .append(" (").append(cols).append(")")
              .append("  ops=").append(spec.allowedOps());
            if (!spec.mutableFields().isEmpty()) {
                sb.append("  mutable=").append(spec.mutableFields());
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
