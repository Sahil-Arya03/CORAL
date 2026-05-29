package org.example.coral.ai;

import org.example.coral.model.IntentResult;
import org.springframework.stereotype.Service;

/**
 * The single, deterministic place where prompts are assembled. No other service builds raw
 * prompt strings. Centralizing this keeps prompt quality, schema-block rendering, and (future)
 * token budgeting under one roof.
 */
@Service
public class PromptBuilderService {

    /** Pure conversational prompt — used for SMALLTALK. No data context, no source mentions. */
    public String conversationalPrompt(String userPrompt, String historyBlock) {
        return """
                You are CreatorOS, an AI assistant for a student/developer. Have a natural,
                helpful conversation. Answer questions, help with thinking, discuss ideas.
                Do NOT mention connecting sources, integrations, or databases unless the user
                explicitly asks about them.
                """
                + historySection(historyBlock)
                + """

                USER MESSAGE:
                """ + userPrompt;
    }

    public String intentPrompt(String userPrompt, java.util.Collection<String> allowedActions) {
        String actionList = String.join(", ", allowedActions);
        return """
                You are the intent extraction stage of CreatorOS. Read the user's message and
                return ONLY a JSON object (no prose) with this exact shape:
                {
                  "summary": "one line of what the user wants",
                  "actionClass": "READ | MUTATE | REFLECT | SMALLTALK",
                  "actionType": "for MUTATE, EXACTLY one of the allowed actions below; otherwise null",
                  "sources": ["any of: github.commits, github.pull_requests, notion.tasks, calendar.events, gmail.emails"],
                  "tags": ["short memory tags"],
                  "entity": "free-text target like 'DBMS task' or null",
                  "targetValue": "desired new value for a mutation, or null"
                }
                Allowed actionType values (use one of these verbatim for MUTATE, else null):
                """ + actionList + """

                Rules:
                - Classify MUTATE only when the user explicitly asks to change/complete/delete/archive something.
                - For MUTATE, actionType MUST be exactly one of the allowed values above — never invent one.
                - Use REFLECT for productivity/focus/insight/pattern questions ("how am I doing", "what should I focus on", "analyse my week").
                - Use SMALLTALK for greetings/thanks.
                - Never invent sources outside the allowed list.

                User message:
                """ + userPrompt;
    }

    public String planningPrompt(IntentResult intent, String schemaBlock) {
        return """
                You are the query planning stage of CreatorOS. Generate safe Coral SQL for the intent.
                You may ONLY use the tables and columns in the schema below. List explicit columns
                (never SELECT *). Use named bindings (:name) for values, never string interpolation.
                Mutations MUST include a bounded WHERE clause.
                For UPDATE statements, SET ONLY the exact column(s) the user asked to change. NEVER
                include audit or timestamp columns such as updated_at, created_at, or committed_at in
                a SET clause — the backend manages those. Use only columns listed as mutable below.

                SOURCE SELECTION: Query ONLY the tables that are genuinely relevant to this specific
                intent. For a targeted question about tasks, query notion.tasks only. For a question
                about emails, query gmail.emails only. For broad overview or cross-source questions,
                query whichever combination of sources is actually useful. Do not generate queries
                for unrelated tables — irrelevant data adds noise and costs latency.

                WHERE CLAUSE EXECUTION: WHERE clauses are fully executed against the database.
                Use precise, specific filters — they reduce result sets and hit DB indexes.
                Prefer filtering on: status, priority, is_unread, is_archived, importance
                (for exact matches) and committed_at, received_at, start_at, due_date (for time ranges).

                Return ONLY JSON of this shape:
                {
                  "rationale": "why these queries and which sources were chosen",
                  "operations": [{"id": "q1", "sql": "...", "bindings": {"name": "value"}}],
                  "joinStrategy": "post-process",
                  "expectedShape": "short description"
                }

                SCHEMA (authoritative — nothing else exists):
                """ + schemaBlock + """

                INTENT:
                """ + Render.intent(intent);
    }

    /**
     * Standard reasoning prompt — used for READ responses.
     * @param historyBlock rendered conversation history (empty string if first turn)
     */
    public String reasoningPrompt(String userPrompt, String contextText,
                                  String memoryBlock, String historyBlock) {
        return """
                You are CreatorOS, an AI productivity copilot for a student/developer. Answer the
                user concisely and helpfully. When context data is provided, use it and surface
                cross-source insights (e.g. deadlines vs. unread email vs. stalled GitHub work).
                When no context was retrieved, answer naturally from your own knowledge — do NOT
                ask the user to connect sources or set up integrations.
                Do not invent data not present in the context.

                FORMATTING — always follow these rules:
                - Use **bold** for task names, project names, and key terms.
                - Use numbered lists (1. 2. 3.) when presenting multiple distinct items or steps.
                - Use bullet points (- item) for sub-details within a numbered item.
                - Keep each paragraph to 2–3 sentences max. Prefer lists over walls of text.
                - Do NOT suggest connecting more sources or integrations — data is already loaded.
                - End with one specific, concrete next action — not a generic list of suggestions.
                """
                + historySection(historyBlock)
                + """

                WHAT WE KNOW ABOUT THE USER:
                """ + (memoryBlock == null || memoryBlock.isBlank() ? "(no memory yet)" : memoryBlock) + """

                CONTEXT (retrieved from the user's connected sources):
                """ + (contextText == null || contextText.isBlank() ? "(no data retrieved)" : contextText) + """

                USER MESSAGE:
                """ + userPrompt;
    }

    /**
     * Reflection prompt — used for REFLECT responses.
     * Instead of listing data, the AI identifies patterns and cross-source connections.
     */
    public String reflectionPrompt(String userPrompt, String contextText,
                                   String memoryBlock, String historyBlock) {
        return """
                You are CreatorOS in reflection mode. You have access to ALL of the user's connected
                sources. Your job is to identify meaningful PATTERNS and CROSS-SOURCE CONNECTIONS,
                not just list data. Think like a productivity coach who can see the full picture.

                Look for:
                - Tasks that are overdue AND have related emails (unresolved deadline stress)
                - Projects with recent commits but stalled tasks (execution vs. planning gap)
                - Upcoming calendar events with no task preparation visible
                - Email threads that should have generated a task but have not
                - Periods of high GitHub activity alongside low task completion, or vice versa

                FORMATTING — always follow these rules:
                - Produce 3–5 insights as a numbered list.
                - Each item must open with a **bold title** (e.g. **Overdue: Finish DBMS Assignment**).
                - Follow the title with 2–3 sentences that reference actual item titles from the context.
                - Close each item with one concrete action the user should take today.
                - Use bullet points (- item) only for sub-details within an insight.
                - Do NOT suggest connecting more sources — use only what is present in the context.
                - If the context genuinely has fewer than 3 items total, say so in one sentence only.
                Do not invent data not present in the context.
                """
                + historySection(historyBlock)
                + """

                WHAT WE KNOW ABOUT THE USER:
                """ + (memoryBlock == null || memoryBlock.isBlank() ? "(no memory yet)" : memoryBlock) + """

                FULL CONTEXT (all connected sources):
                """ + (contextText == null || contextText.isBlank() ? "(no data retrieved)" : contextText) + """

                USER MESSAGE:
                """ + userPrompt;
    }

    /**
     * Long-term memory extraction — called after each READ/REFLECT exchange.
     * Returns JSON {"memories":[...]} with only genuinely important personal facts.
     */
    public String memoryExtractionPrompt(String userMessage, String assistantResponse) {
        return """
                You are a memory extraction stage. Analyze this exchange and extract important
                personal facts about the user worth remembering long-term.

                Only extract facts that are durable and personal:
                - Goals, projects, and things they're studying or building
                - Preferences: how they like to work, what they find hard, what they value
                - Important identity context: role, field, major constraints

                Do NOT extract: one-time queries, task status, greetings, or things already
                obvious from system data. Be very selective — most exchanges have nothing worth saving.

                Importance: 5=critical life/career goal, 4=strong lasting preference,
                3=useful background context. Skip anything below 3.

                Return ONLY this JSON, no prose:
                {"memories":[{"content":"...","category":"goal|preference|habit|observation","tags":["..."],"importance":3}]}
                Return {"memories":[]} if nothing is worth saving.

                USER: """ + userMessage + """

                ASSISTANT: """ + assistantResponse;
    }

    private static String historySection(String historyBlock) {
        if (historyBlock == null || historyBlock.isBlank()) return "";
        return """

                CONVERSATION HISTORY (this session, oldest first — use for follow-up context):
                """ + historyBlock;
    }

    private static final class Render {
        static String intent(IntentResult i) {
            return "summary=" + i.summary()
                    + "\nactionClass=" + i.actionClass()
                    + "\nactionType=" + i.actionType()
                    + "\nsources=" + i.sources()
                    + "\nentity=" + i.entity()
                    + "\ntargetValue=" + i.targetValue();
        }
    }
}
