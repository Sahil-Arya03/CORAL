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
import org.example.coral.security.PolicyEngine;
import org.example.coral.security.SqlValidationService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end pipeline test using the offline (heuristic) path — no model, no network. Verifies
 * intent -> schema -> plan -> validate -> execute -> aggregate -> memory -> reason and the
 * full mutation/confirm handshake, exactly as the architecture specifies.
 */
class OrchestrationServiceTest {

    private final OrchestrationService orchestrator = build();

    private static OrchestrationService build() {
        SchemaCatalog catalog = new SchemaCatalog();
        AiGateway ai = new AiGateway((org.springframework.ai.chat.model.ChatModel) null); // force heuristics
        PromptBuilderService prompts = new PromptBuilderService();
        CoralClientHolder coral = new CoralClientHolder();

        var policy = new PolicyEngine();
        var intent = new IntentExtractionService(ai, prompts, policy);
        var classification = new ActionClassificationService();
        var schemaContext = new SchemaContextService(catalog);
        var planning = new QueryPlanningService(ai, prompts, catalog);
        var validation = new SqlValidationService(catalog);
        var aggregation = new ContextAggregationService();
        var memory = new org.example.coral.memory.MemoryInjectionService(null); // null jdbc → offline no-op (exceptions caught)
        var reasoning = new AIReasoningService(ai, prompts);
        var pending = new PendingActionStore();
        var conversations = new org.example.coral.persistence.ConversationRepository(null);
        var queryLogs = new org.example.coral.persistence.QueryLogRepository(null);
        var actionLogs = new org.example.coral.persistence.ActionLogRepository(null);

        return new OrchestrationService(intent, classification, schemaContext, planning, validation,
                policy, coral.read, coral.action, aggregation, memory, reasoning, pending,
                conversations, queryLogs, actionLogs, null, null);
    }

    @Test
    void readPathAggregatesAcrossSources() {
        ChatResponse r = orchestrator.handle(new ChatRequest("show my tasks and unread emails", null));
        assertNull(r.pending());
        assertFalse(r.text().isBlank());
        assertTrue(r.text().contains("Finish DBMS assignment"), "should surface task fixture: " + r.text());
        assertTrue(r.text().contains("DBMS assignment deadline"), "should surface email fixture: " + r.text());
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

    /** Builds the coral read/action services (their CoralClient dependency is package-private). */
    private static final class CoralClientHolder {
        final CoralExecutionService read;
        final ActionExecutionService action;

        CoralClientHolder() {
            try {
                Class<?> clientClass = Class.forName("org.example.coral.coral.CoralClient");
                Constructor<?> ctor = clientClass.getDeclaredConstructor();
                ctor.setAccessible(true);
                Object client = ctor.newInstance();

                Constructor<CoralExecutionService> readCtor =
                        CoralExecutionService.class.getDeclaredConstructor(clientClass);
                readCtor.setAccessible(true);
                this.read = readCtor.newInstance(client);

                Constructor<ActionExecutionService> actionCtor =
                        ActionExecutionService.class.getDeclaredConstructor(clientClass);
                actionCtor.setAccessible(true);
                this.action = actionCtor.newInstance(client);
            } catch (Exception e) {
                throw new RuntimeException("Failed to build Coral services for test", e);
            }
        }
    }
}
