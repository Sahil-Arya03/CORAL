package org.example.coral.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Mutable carrier threaded through the orchestration pipeline. Each stage reads what it needs
 * and appends its output. Not a God object — it holds only pipeline state, no behavior.
 */
public class OrchestrationContext {

    private final String userPrompt;
    private final UUID sessionId;
    private final long userId;
    private final String clerkUserId;

    private IntentResult intent;
    private java.util.Set<String> allowedTables;
    private CoralQueryPlan plan;
    private final List<ValidatedQuery> validatedQueries = new ArrayList<>();
    private PolicyDecision policyDecision;
    private final List<CoralResultSet> results = new ArrayList<>();
    private List<TimelineEvent> aggregated = new ArrayList<>();
    private String memoryBlock = "";
    private String pendingActionToken;
    private List<Map<String, Object>> conversationHistory = List.of();

    public OrchestrationContext(String userPrompt, UUID sessionId, long userId, String clerkUserId) {
        this.userPrompt = userPrompt;
        this.sessionId = sessionId;
        this.userId = userId;
        this.clerkUserId = clerkUserId;
    }

    public String userPrompt()    { return userPrompt; }
    public UUID sessionId()       { return sessionId; }
    public long userId()          { return userId; }
    public String clerkUserId()   { return clerkUserId; }

    public IntentResult intent() { return intent; }
    public void setIntent(IntentResult intent) { this.intent = intent; }

    public java.util.Set<String> allowedTables() { return allowedTables; }
    public void setAllowedTables(java.util.Set<String> allowedTables) { this.allowedTables = allowedTables; }

    public CoralQueryPlan plan() { return plan; }
    public void setPlan(CoralQueryPlan plan) { this.plan = plan; }

    public List<ValidatedQuery> validatedQueries() { return validatedQueries; }
    public void addValidatedQuery(ValidatedQuery q) { this.validatedQueries.add(q); }

    public PolicyDecision policyDecision() { return policyDecision; }
    public void setPolicyDecision(PolicyDecision policyDecision) { this.policyDecision = policyDecision; }

    public List<CoralResultSet> results() { return results; }
    public void addResult(CoralResultSet rs) { this.results.add(rs); }

    public List<TimelineEvent> aggregated() { return aggregated; }
    public void setAggregated(List<TimelineEvent> aggregated) { this.aggregated = aggregated; }

    public String memoryBlock() { return memoryBlock; }
    public void setMemoryBlock(String memoryBlock) { this.memoryBlock = memoryBlock; }

    public String pendingActionToken() { return pendingActionToken; }
    public void setPendingActionToken(String pendingActionToken) { this.pendingActionToken = pendingActionToken; }

    public List<Map<String, Object>> conversationHistory() { return conversationHistory; }
    public void setConversationHistory(List<Map<String, Object>> conversationHistory) {
        this.conversationHistory = conversationHistory != null ? conversationHistory : List.of();
    }
}
