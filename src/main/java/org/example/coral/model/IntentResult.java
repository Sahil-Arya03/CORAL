package org.example.coral.model;

import java.util.List;

/**
 * Structured output of the IntentExtractionService (AI stage 1).
 * Produced from the user's prompt only — never from fetched data.
 */
public record IntentResult(
        String summary,
        ActionClass actionClass,
        String actionType,        // e.g. "task.update_status"; null for pure reads
        List<String> sources,     // catalog table names the intent touches, e.g. ["notion.tasks"]
        List<String> tags,        // memory-retrieval tags, e.g. ["tasks","deadline"]
        String entity,            // free-text target, e.g. "DBMS task"
        String targetValue        // desired new value for mutations, e.g. "done"
) {
    public boolean isSmalltalk() {
        return actionClass == ActionClass.SMALLTALK;
    }

    public boolean isMutation() {
        return actionClass == ActionClass.MUTATE;
    }
}
