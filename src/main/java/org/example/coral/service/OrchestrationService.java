package org.example.coral.service;

import org.example.coral.ai.AIReasoningService;
import org.example.coral.ai.IntentExtractionService;
import org.example.coral.ai.QueryPlanningService;
import org.example.coral.analytics.ContextAggregationService;
import org.example.coral.coral.ActionExecutionService;
import org.example.coral.coral.CoralExecutionService;
import org.example.coral.dto.ChatDtos.ActionResult;
import org.example.coral.dto.ChatDtos.ChatRequest;
import org.example.coral.dto.ChatDtos.ChatResponse;
import org.example.coral.dto.ChatDtos.PendingAction;
import org.example.coral.memory.LongTermMemoryService;
import org.example.coral.memory.MemoryInjectionService;
import org.example.coral.model.ActionClass;
import org.example.coral.model.CoralQueryPlan;
import org.example.coral.model.CoralResultSet;
import org.example.coral.model.IntentResult;
import org.example.coral.model.OrchestrationContext;
import org.example.coral.model.PolicyDecision;
import org.example.coral.model.ValidatedQuery;
import org.example.coral.persistence.ActionLogRepository;
import org.example.coral.persistence.ConversationRepository;
import org.example.coral.persistence.QueryLogRepository;
import org.example.coral.persistence.ThreadRepository;
import org.example.coral.security.PolicyEngine;
import org.example.coral.security.SqlValidationService;
import org.example.coral.security.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The conductor. Drives the fixed orchestration pipeline stage by stage, threading a single
 * OrchestrationContext and applying short-circuits per action class. The AI plans; this service
 * (with validation + policy) governs and executes. The AI never touches Coral directly.
 */
@Service
public class OrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(OrchestrationService.class);
    private static final long DEMO_USER_ID = 1L;

    private final IntentExtractionService intentExtraction;
    private final ActionClassificationService classification;
    private final SchemaContextService schemaContext;
    private final QueryPlanningService planning;
    private final SqlValidationService validation;
    private final PolicyEngine policy;
    private final CoralExecutionService coralExecution;
    private final ActionExecutionService actionExecution;
    private final ContextAggregationService aggregation;
    private final MemoryInjectionService memory;
    private final AIReasoningService reasoning;
    private final PendingActionStore pendingStore;
    private final ConversationRepository conversations;
    private final QueryLogRepository queryLogs;
    private final ActionLogRepository actionLogs;
    private final ThreadRepository threadRepo;
    private final LongTermMemoryService longTermMemory;

    public OrchestrationService(IntentExtractionService intentExtraction,
                                ActionClassificationService classification,
                                SchemaContextService schemaContext,
                                QueryPlanningService planning,
                                SqlValidationService validation,
                                PolicyEngine policy,
                                CoralExecutionService coralExecution,
                                ActionExecutionService actionExecution,
                                ContextAggregationService aggregation,
                                MemoryInjectionService memory,
                                AIReasoningService reasoning,
                                PendingActionStore pendingStore,
                                ConversationRepository conversations,
                                QueryLogRepository queryLogs,
                                ActionLogRepository actionLogs,
                                ThreadRepository threadRepo,
                                LongTermMemoryService longTermMemory) {
        this.intentExtraction = intentExtraction;
        this.classification = classification;
        this.schemaContext = schemaContext;
        this.planning = planning;
        this.validation = validation;
        this.policy = policy;
        this.coralExecution = coralExecution;
        this.actionExecution = actionExecution;
        this.aggregation = aggregation;
        this.memory = memory;
        this.reasoning = reasoning;
        this.pendingStore = pendingStore;
        this.conversations = conversations;
        this.queryLogs = queryLogs;
        this.actionLogs = actionLogs;
        this.threadRepo = threadRepo;
        this.longTermMemory = longTermMemory;
    }

    public ChatResponse handle(ChatRequest req) {
        UUID sessionId = req.sessionId() != null ? req.sessionId() : UUID.randomUUID();
        OrchestrationContext ctx = new OrchestrationContext(req.prompt(), sessionId, DEMO_USER_ID);

        // Fetch history BEFORE recording the current user turn so it only contains prior turns.
        ctx.setConversationHistory(conversations.getRecentHistory(DEMO_USER_ID, sessionId, 10));

        boolean isFirstTurn = ctx.conversationHistory().isEmpty();

        conversations.record(DEMO_USER_ID, sessionId, "user", req.prompt(), null, null);
        ChatResponse response = process(ctx);
        conversations.record(DEMO_USER_ID, sessionId, "assistant", response.text(), ctx.intent(), null);
        if (threadRepo != null) {
            threadRepo.touch(sessionId);
            if (isFirstTurn && ctx.intent() != null) {
                threadRepo.updateTitle(sessionId, DEMO_USER_ID, deriveTitle(ctx.intent().summary()));
            }
        }
        return response;
    }

    private ChatResponse process(OrchestrationContext ctx) {
        // Stage 1-2: intent + routing.
        IntentResult intent = intentExtraction.extract(ctx.userPrompt());
        ctx.setIntent(intent);
        ActionClass route = classification.classify(intent);
        log.info("intent='{}' class={} actionType={}", intent.summary(), route, intent.actionType());

        // Short-circuit: smalltalk needs no data — use the conversational path.
        if (route == ActionClass.SMALLTALK) {
            return ChatResponse.text(reasoning.chat(ctx));
        }

        // Stage 3: schema-context injection.
        Set<String> allowed = schemaContext.resolveAllowedTables(intent);
        ctx.setAllowedTables(allowed);

        // Stage 4: AI query planning.
        CoralQueryPlan plan = planning.plan(intent, allowed);
        ctx.setPlan(plan);

        if (route == ActionClass.MUTATE) {
            return handleMutation(ctx, intent, plan, allowed);
        }

        // READ / REFLECT: validate + execute reads, aggregate, inject memory, reason.
        for (CoralQueryPlan.Operation op : plan.operations()) {
            try {
                ValidatedQuery vq = validation.validate(op, allowed);
                if (vq.isMutation()) {
                    log.warn("Skipping mutation produced on a read path: {}", op.id());
                    continue;
                }
                ctx.addValidatedQuery(vq);
                CoralResultSet rs = coralExecution.execute(vq);
                ctx.addResult(rs);
                queryLogs.record(ctx.userId(), ctx.sessionId(), ctx.userPrompt(), op.sql(),
                        "ok", null, (int) rs.latencyMs());
            } catch (ValidationException e) {
                log.warn("Rejected planned query {}: {}", op.id(), e.getMessage());
                queryLogs.record(ctx.userId(), ctx.sessionId(), ctx.userPrompt(), op.sql(),
                        "rejected", e.getMessage(), null);
            }
        }

        ctx.setAggregated(aggregation.aggregate(ctx.results()));
        ctx.setMemoryBlock(memory.buildMemoryBlock(ctx.userId(), intent.tags()));

        // REFLECT uses a cross-source pattern prompt; READ uses the standard answer prompt.
        String text = (route == ActionClass.REFLECT) ? reasoning.reflect(ctx) : reasoning.reason(ctx);
        if (longTermMemory != null) {
            longTermMemory.extractAndStoreAsync(ctx.userId(), ctx.userPrompt(), text);
        }
        return new ChatResponse(text, List.of(), List.of(), null);
    }

    private ChatResponse handleMutation(OrchestrationContext ctx, IntentResult intent,
                                        CoralQueryPlan plan, Set<String> allowed) {
        if (plan.operations().isEmpty()) {
            return ChatResponse.text("I couldn't form a safe action for that request.");
        }
        CoralQueryPlan.Operation op = plan.operations().get(0);
        ValidatedQuery vq;
        try {
            vq = validation.validate(op, allowed);
        } catch (ValidationException e) {
            queryLogs.record(ctx.userId(), ctx.sessionId(), ctx.userPrompt(), op.sql(),
                    "rejected", e.getMessage(), null);
            actionLogs.record(ctx.userId(), intent.actionType(), null, op.sql(),
                    op.bindings(), null, 0, null, "failed");
            return ChatResponse.text("I planned an action I'm not allowed to run: " + e.getMessage());
        }
        if (!vq.isMutation()) {
            return ChatResponse.text("That request didn't resolve to a change I can make.");
        }
        queryLogs.record(ctx.userId(), ctx.sessionId(), ctx.userPrompt(), vq.normalizedSql(),
                "ok", null, null);

        int estimated = actionExecution.estimateAffectedRows(vq);
        PolicyDecision decision = policy.evaluate(intent, vq, estimated);

        return switch (decision.verdict()) {
            case DENY -> {
                actionLogs.record(ctx.userId(), intent.actionType(), vq.table(), vq.normalizedSql(),
                        vq.bindings(), null, 0, "DENY", "denied");
                yield ChatResponse.text("I can't do that — " + decision.reason() + ".");
            }
            case CONFIRM -> {
                PendingActionStore.Pending p = pendingStore.create(intent, vq, estimated);
                PendingAction pending = new PendingAction(
                        p.token(),
                        "This will " + vq.type() + " " + estimated + " row(s) in " + vq.table(),
                        estimated,
                        intent.actionType());
                yield new ChatResponse(
                        "This action needs your confirmation. " + decision.reason() + ".",
                        List.of(), List.of(), pending);
            }
            case ALLOW -> {
                int rows = actionExecution.execute(vq);
                actionLogs.record(ctx.userId(), intent.actionType(), vq.table(), vq.normalizedSql(),
                        vq.bindings(), null, rows, "ALLOW", "executed");
                yield ChatResponse.text("Done — " + rows + " row(s) updated via " + intent.actionType() + ".");
            }
        };
    }

    /** Turns an intent summary into a compact, readable thread title. */
    private static String deriveTitle(String summary) {
        if (summary == null || summary.isBlank()) return "New Chat";
        // Capitalise and trim to 60 chars.
        String t = summary.trim();
        t = Character.toUpperCase(t.charAt(0)) + t.substring(1);
        return t.length() > 60 ? t.substring(0, 59) + "…" : t;
    }

    /** Second turn of the confirmation handshake: re-validate then execute. */
    public ActionResult confirm(String token) {
        var maybe = pendingStore.consume(token);
        if (maybe.isEmpty()) {
            return new ActionResult(false, "Confirmation expired or not found.", 0);
        }
        PendingActionStore.Pending p = maybe.get();
        ValidatedQuery stored = p.query();
        try {
            // Defensive re-validation of the exact SQL we are about to run.
            ValidatedQuery revalidated = validation.validate(
                    new CoralQueryPlan.Operation(stored.operationId(), stored.normalizedSql(), stored.bindings()),
                    Set.of(stored.table()));
            int rows = actionExecution.execute(revalidated);
            actionLogs.record(DEMO_USER_ID, p.intent().actionType(), revalidated.table(),
                    revalidated.normalizedSql(), revalidated.bindings(), null, rows, "CONFIRM", "executed");
            return new ActionResult(true, "Executed " + p.intent().actionType(), rows);
        } catch (ValidationException e) {
            actionLogs.record(DEMO_USER_ID, p.intent().actionType(), stored.table(),
                    stored.normalizedSql(), stored.bindings(), null, 0, "CONFIRM", "failed");
            return new ActionResult(false, "Re-validation failed: " + e.getMessage(), 0);
        }
    }
}
