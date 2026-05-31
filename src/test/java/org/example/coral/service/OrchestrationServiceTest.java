package org.example.coral.service;

import org.example.coral.ai.AIReasoningService;
import org.example.coral.ai.AiGateway;
import org.example.coral.ai.IntentExtractionService;
import org.example.coral.ai.PromptBuilderService;
import org.example.coral.ai.QueryPlanningService;
import org.example.coral.analytics.ContextAggregationService;
import org.example.coral.coral.ActionExecutionService;
import org.example.coral.coral.CoralExecutionService;
import org.example.coral.coral.SchemaCatalog;
import org.example.coral.dto.ChatDtos.ActionResult;
import org.example.coral.dto.ChatDtos.ChatRequest;
import org.example.coral.dto.ChatDtos.ChatResponse;
import org.example.coral.model.CoralResultSet;
import org.example.coral.model.ValidatedQuery;
import org.example.coral.security.PolicyEngine;
import org.example.coral.security.SqlValidationService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * End-to-end pipeline test using the offline (heuristic) path — no model, no network, no DB.
 * CoralExecutionService and ActionExecutionService are mocked with known test data so the
 * full orchestration pipeline (intent → schema → plan → validate → execute → aggregate →
 * memory → reason) and the mutation/confirm handshake can be verified without a live database.
 *
 * Test data lives here, not in CoralClient — CoralClient only ever queries the real database.
 */
class OrchestrationServiceTest {

    private final CoralExecutionService coralMock   = mock(CoralExecutionService.class);
    private final ActionExecutionService actionMock  = mock(ActionExecutionService.class);
    private final OrchestrationService orchestrator  = build();

    private OrchestrationService build() {
        // Mock read execution — returns test data per table so pipeline assertions hold
        when(coralMock.execute(any(ValidatedQuery.class), anyString()))
                .thenAnswer(inv -> {
                    ValidatedQuery vq = inv.getArgument(0);
                    return testData(vq.table());
                });

        // Mock mutation execution (execute now takes clerkUserId as second arg)
        when(actionMock.estimateAffectedRows(any())).thenReturn(1);
        when(actionMock.execute(any(ValidatedQuery.class), anyString())).thenReturn(1);

        SchemaCatalog catalog = new SchemaCatalog();
        AiGateway ai = new AiGateway((org.springframework.ai.chat.model.ChatModel) null); // heuristics only
        PromptBuilderService prompts = new PromptBuilderService();

        var policy         = new PolicyEngine();
        var intent         = new IntentExtractionService(ai, prompts, policy);
        var classification = new ActionClassificationService();
        var schemaContext  = new SchemaContextService(catalog);
        var planning       = new QueryPlanningService(ai, prompts, catalog);
        var validation     = new SqlValidationService(catalog);
        var aggregation    = new ContextAggregationService();
        var memory         = new org.example.coral.memory.MemoryInjectionService(null);
        var reasoning      = new AIReasoningService(ai, prompts);
        var pending        = new PendingActionStore();
        var conversations  = new org.example.coral.persistence.ConversationRepository(null);
        var queryLogs      = new org.example.coral.persistence.QueryLogRepository(null);
        var actionLogs     = new org.example.coral.persistence.ActionLogRepository(null);

        return new OrchestrationService(intent, classification, schemaContext, planning, validation,
                policy, coralMock, actionMock, aggregation, memory, reasoning, pending,
                conversations, queryLogs, actionLogs, null, null);
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    void readPathAggregatesAcrossSources() {
        ChatResponse r = orchestrator.handle(new ChatRequest("show my tasks and unread emails", null));
        assertNull(r.pending());
        assertFalse(r.text().isBlank());
        assertTrue(r.text().contains("Finish DBMS assignment"),
                "should surface task data: " + r.text());
        assertTrue(r.text().contains("DBMS assignment deadline"),
                "should surface email data: " + r.text());
    }

    @Test
    void smalltalkShortCircuits() {
        ChatResponse r = orchestrator.handle(new ChatRequest("hello there", null));
        assertNull(r.pending());
        assertNotNull(r.text());
    }

    @Test
    void allowedMutationExecutesImmediately() {
        ChatResponse r = orchestrator.handle(new ChatRequest("mark my DBMS task as done", null));
        assertNull(r.pending(), "single-row status update is auto-approved");
        assertTrue(r.text().startsWith("Done"), "expected execution confirmation: " + r.text());
    }

    @Test
    void destructiveMutationRequiresConfirmationThenExecutes() {
        ChatResponse r = orchestrator.handle(new ChatRequest("delete my duplicate reminders", null));
        assertNotNull(r.pending(), "delete should require confirmation");
        assertEquals("reminder.delete_dup", r.pending().actionType());

        ActionResult result = orchestrator.confirm(r.pending().token());
        assertTrue(result.executed(), "confirmed action should execute: " + result.message());
    }

    @Test
    void expiredOrUnknownTokenIsRejected() {
        ActionResult result = orchestrator.confirm("not-a-real-token");
        assertFalse(result.executed());
    }

    // ── Test data ─────────────────────────────────────────────────────────────

    /** Per-table test rows injected by the CoralExecutionService mock. */
    private static CoralResultSet testData(String table) {
        Instant now = Instant.now();
        return switch (table) {
            case "notion.tasks" -> new CoralResultSet("q", table, List.of(
                    Map.of("id", "t1", "title", "Finish DBMS assignment",
                           "status", "todo", "priority", "high",
                           "due_date", now.minus(1, ChronoUnit.DAYS).toString(),
                           "project", "DBMS",
                           "updated_at", now.minus(3, ChronoUnit.DAYS).toString()),
                    Map.of("id", "t2", "title", "OS lab report",
                           "status", "in_progress", "priority", "medium",
                           "due_date", now.plus(2, ChronoUnit.DAYS).toString(),
                           "project", "OS",
                           "updated_at", now.minus(1, ChronoUnit.DAYS).toString())
            ), 0);
            case "gmail.emails" -> new CoralResultSet("q", table, List.of(
                    Map.of("id", "m1", "subject", "DBMS assignment deadline moved to tomorrow",
                           "sender", "prof@univ.edu", "snippet", "Please submit by EOD tomorrow.",
                           "is_unread", "true",
                           "received_at", now.minus(4, ChronoUnit.HOURS).toString(),
                           "importance", "high", "is_archived", "false"),
                    Map.of("id", "m2", "subject", "Weekly newsletter",
                           "sender", "news@list.com", "snippet", "Top stories this week.",
                           "is_unread", "true",
                           "received_at", now.minus(1, ChronoUnit.DAYS).toString(),
                           "importance", "low", "is_archived", "false")
            ), 0);
            case "github.commits" -> new CoralResultSet("q", table, List.of(
                    Map.of("sha", "a1b2", "repo", "os-lab", "author", "arya",
                           "message", "wip: scheduler",
                           "committed_at", now.minus(2, ChronoUnit.DAYS).toString())
            ), 0);
            case "github.pull_requests" -> new CoralResultSet("q", table, List.of(
                    Map.of("id", "pr1", "repo", "os-lab", "title", "Scheduler v1",
                           "state", "open",
                           "created_at", now.minus(3, ChronoUnit.DAYS).toString(),
                           "merged_at", "")
            ), 0);
            case "calendar.events" -> new CoralResultSet("q", table, List.of(
                    Map.of("id", "e1", "title", "DBMS lecture",
                           "start_at", now.plus(4, ChronoUnit.HOURS).toString(),
                           "end_at", now.plus(5, ChronoUnit.HOURS).toString(),
                           "attendees_count", 40, "is_meeting", "true",
                           "description", "Week 11", "location", "Lecture Hall A")
            ), 0);
            default -> new CoralResultSet("q", table, List.of(), 0);
        };
    }
}
