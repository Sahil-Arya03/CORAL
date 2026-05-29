package org.example.coral.coral;

import org.example.coral.model.ValidatedQuery.StatementType;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.example.coral.model.ValidatedQuery.StatementType.DELETE;
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
                Set.of("sha", "repo", "author", "message", "committed_at"),
                Set.of(SELECT), Set.of()));

        register(new TableSpec("github.pull_requests",
                Set.of("id", "repo", "title", "state", "created_at", "merged_at"),
                Set.of(SELECT), Set.of()));

        register(new TableSpec("notion.tasks",
                Set.of("id", "title", "status", "priority", "due_date", "project", "updated_at"),
                Set.of(SELECT, UPDATE, DELETE),
                Set.of("status", "due_date", "priority")));

        register(new TableSpec("calendar.events",
                Set.of("id", "title", "start_at", "end_at", "attendees_count", "is_meeting"),
                Set.of(SELECT), Set.of()));

        register(new TableSpec("gmail.emails",
                Set.of("id", "subject", "sender", "snippet", "is_unread", "received_at", "importance", "is_archived"),
                Set.of(SELECT, UPDATE, DELETE),
                Set.of("is_archived", "is_unread")));
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

    /** Human-readable schema block injected into AI planning prompts. */
    public String renderSchemaBlock(Set<String> tableNames) {
        StringBuilder sb = new StringBuilder();
        for (String name : tableNames) {
            TableSpec spec = tables.get(name);
            if (spec == null) continue;
            sb.append(name)
              .append(" (").append(String.join(", ", spec.columns())).append(")")
              .append("  ops=").append(spec.allowedOps());
            if (!spec.mutableFields().isEmpty()) {
                sb.append("  mutable=").append(spec.mutableFields());
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
