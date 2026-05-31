package org.example.coral.model;

import java.util.List;
import java.util.Map;

/**
 * Output of SqlValidationService. Existence of this object is the guarantee that the
 * SQL passed every structural safety check. Only CoralExecutionService / ActionExecutionService
 * may consume it.
 */
public record ValidatedQuery(
        String operationId,
        StatementType type,
        String table,             // primary target table (catalog name)
        List<String> tables,      // all referenced tables
        List<String> writeFields, // columns written by an UPDATE (empty for SELECT/DELETE)
        String normalizedSql,     // LIMIT-injected / canonicalized form actually run
        Map<String, Object> bindings
) {
    public enum StatementType { SELECT, INSERT, UPDATE, DELETE }

    public boolean isMutation() {
        return type != StatementType.SELECT;
    }
}
