package org.example.coral.coral;

import org.example.coral.model.CoralResultSet;
import org.example.coral.model.ValidatedQuery;
import org.springframework.stereotype.Service;

/**
 * The ONLY component permitted to run read queries against Coral. Accepts a ValidatedQuery —
 * whose existence guarantees the SQL already passed structural validation — and never a raw string.
 */
@Service
public class CoralExecutionService {

    private final CoralClient client;

    public CoralExecutionService(CoralClient client) {
        this.client = client;
    }

    public CoralResultSet execute(ValidatedQuery query) {
        if (query.isMutation()) {
            throw new IllegalArgumentException(
                    "CoralExecutionService runs reads only; mutations must go through ActionExecutionService");
        }
        long start = System.currentTimeMillis();
        var rows = client.executeRead(query);
        long latency = System.currentTimeMillis() - start;
        return new CoralResultSet(query.operationId(), query.table(), rows, latency);
    }
}
