package org.example.coral.coral;

import org.example.coral.model.ValidatedQuery;
import org.springframework.stereotype.Service;

/**
 * The ONLY component permitted to run mutating queries against Coral. Accepts a ValidatedQuery
 * that has already cleared validation AND a non-DENY policy decision (enforced by the caller).
 *
 * All mutations are scoped to the calling user:
 *  - INSERT: id and user_id injected by CoralClient.executeInsert()
 *  - UPDATE/DELETE: AND user_id = :_userId appended to WHERE by CoralClient.executeMutationScoped()
 */
@Service
public class ActionExecutionService {

    private final CoralClient client;

    public ActionExecutionService(CoralClient client) {
        this.client = client;
    }

    /** Conservative affected-row estimate used by the policy row-ceiling check. */
    public int estimateAffectedRows(ValidatedQuery query) {
        if (query.type() == ValidatedQuery.StatementType.INSERT) return 1;
        return client.estimateMutation(query);
    }

    /**
     * Execute a validated mutation scoped to the given Clerk user ID.
     * INSERT: injects id + user_id into the row.
     * UPDATE / DELETE: appends AND user_id = :_userId to the WHERE clause.
     */
    public int execute(ValidatedQuery query, String clerkUserId) {
        if (!query.isMutation()) {
            throw new IllegalArgumentException("ActionExecutionService runs mutations only");
        }
        return switch (query.type()) {
            case INSERT -> client.executeInsert(query, clerkUserId);
            default     -> client.executeMutationScoped(query, clerkUserId);
        };
    }
}
