package org.example.coral.coral;

import org.example.coral.model.ValidatedQuery;
import org.springframework.stereotype.Service;

/**
 * The ONLY component permitted to run mutating queries against Coral. Accepts a ValidatedQuery
 * that has already cleared validation AND a non-DENY policy decision (enforced by the caller).
 * Every execution should be paired with an action_logs entry (before-snapshot) in production.
 */
@Service
public class ActionExecutionService {

    private final CoralClient client;

    public ActionExecutionService(CoralClient client) {
        this.client = client;
    }

    /** Conservative affected-row estimate used by the policy row-ceiling check (real impl: COUNT(*)). */
    public int estimateAffectedRows(ValidatedQuery query) {
        return client.estimateMutation(query);
    }

    public int execute(ValidatedQuery query) {
        if (!query.isMutation()) {
            throw new IllegalArgumentException("ActionExecutionService runs mutations only");
        }
        return client.executeMutation(query);
    }
}
