package org.example.coral.coral;

import org.example.coral.model.ValidatedQuery.StatementType;

import java.util.Set;

/**
 * Curated metadata for a single Coral-exposed table. Drives schema injection (what the AI
 * is told exists) and validation (what is structurally permitted).
 */
public record TableSpec(
        String name,
        Set<String> columns,
        Set<StatementType> allowedOps,
        Set<String> mutableFields
) {
    public boolean hasColumn(String column) {
        return columns.contains(column);
    }

    public boolean allows(StatementType op) {
        return allowedOps.contains(op);
    }

    public boolean isMutable(String field) {
        return mutableFields.contains(field);
    }
}
