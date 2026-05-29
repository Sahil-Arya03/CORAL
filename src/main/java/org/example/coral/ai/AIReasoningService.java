package org.example.coral.ai;

import org.example.coral.model.OrchestrationContext;
import org.example.coral.model.TimelineEvent;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * AI stage 3: synthesize the final, human-facing answer from aggregated context + memory.
 * Falls back to a deterministic narrative built from the retrieved context when the model is
 * unavailable, so the user always gets a grounded response.
 */
@Service
public class AIReasoningService {

    private final AiGateway ai;
    private final PromptBuilderService prompts;

    public AIReasoningService(AiGateway ai, PromptBuilderService prompts) {
        this.ai = ai;
        this.prompts = prompts;
    }

    /** SMALLTALK response — pure conversation, no data context. */
    public String chat(OrchestrationContext ctx) {
        String historyBlock = renderHistory(ctx.conversationHistory());
        String prompt = prompts.conversationalPrompt(ctx.userPrompt(), historyBlock);
        return ai.complete(prompt).orElse("Hey! How can I help you?");
    }

    /** Standard READ response — answers the user's question from retrieved context. */
    public String reason(OrchestrationContext ctx) {
        String contextText  = renderContext(ctx.aggregated());
        String historyBlock = renderHistory(ctx.conversationHistory());
        String prompt = prompts.reasoningPrompt(ctx.userPrompt(), contextText,
                ctx.memoryBlock(), historyBlock);
        return ai.complete(prompt).orElseGet(() -> fallback(ctx, contextText));
    }

    /** REFLECT response — identifies cross-source patterns instead of listing data. */
    public String reflect(OrchestrationContext ctx) {
        String contextText  = renderContext(ctx.aggregated());
        String historyBlock = renderHistory(ctx.conversationHistory());
        String prompt = prompts.reflectionPrompt(ctx.userPrompt(), contextText,
                ctx.memoryBlock(), historyBlock);
        return ai.complete(prompt).orElseGet(() -> fallback(ctx, contextText));
    }

    // ── Rendering ─────────────────────────────────────────────────────────────

    private String renderContext(List<TimelineEvent> events) {
        if (events == null || events.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (TimelineEvent e : events) {
            sb.append("- [").append(e.source()).append('/').append(e.type()).append("] ")
              .append(e.title());
            if (e.summary() != null && !e.summary().isBlank()) {
                sb.append(" — ").append(e.summary());
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private String renderHistory(List<Map<String, Object>> history) {
        if (history == null || history.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> turn : history) {
            sb.append('[').append(turn.get("role")).append("] ")
              .append(turn.get("content")).append('\n');
        }
        return sb.toString();
    }

    private String fallback(OrchestrationContext ctx, String contextText) {
        if (contextText.isBlank()) {
            return "I don't have any connected-source data to reason over yet for: \""
                    + ctx.userPrompt() + "\".";
        }
        long count = ctx.aggregated().size();
        return "Here's what I found across your sources (" + count + " items):\n"
                + contextText
                + "\n(Generated without a live model — connect an API key for richer reasoning.)";
    }
}
