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

                Allowed actionType values — use EXACTLY one for MUTATE, null for everything else:
                """ + actionList + """

                Action guide:
                - task.create           → user wants to ADD a new task ("create a task", "add to my todo", "remind me to")
                - task.update_status    → mark task done/in-progress/todo
                - task.update_deadline  → change a due date
                - task.update_priority  → change priority (high/medium/low)
                - task.update_title     → rename a task
                - task.update_project   → move task to a different project
                - task.delete           → delete/remove a task permanently (requires confirmation)
                - reminder.archive      → archive an email
                - reminder.unarchive    → unarchive an email
                - reminder.mark_read    → mark email(s) as read
                - reminder.delete_dup   → delete duplicate/spam emails (requires confirmation)

                Classification rules:
                - MUTATE only when the user explicitly wants to change, create, delete, or archive something.
                - READ for any query about existing data ("show me", "what are", "list my", "do I have").
                - REFLECT for productivity/insight questions ("how am I doing", "what should I focus on").
                - SMALLTALK for greetings and thanks only.
                - Never invent sources outside the allowed list.
                - entity = the specific item being acted on (task title, email subject, etc.)
                - targetValue = the new value for updates (e.g. "done", "high", "2024-12-15") or task details for create

                User message:
                """ + userPrompt;
    }

    public String planningPrompt(IntentResult intent, String schemaBlock) {
        return """
                You are the query planning stage of CreatorOS. Generate safe Coral SQL for the intent.

                ABSOLUTE RULES — violating any of these causes data to be silently lost:
                1. NEVER reference the user_id or id column in any SQL — not in SELECT, not in
                   WHERE, not in INSERT column lists. The backend injects both automatically.
                   If you add your own user_id filter the double-condition returns zero rows.
                2. List ONLY explicit columns (never SELECT *).
                3. Use named bindings (:name) for values — never string interpolation.
                4. UPDATE and DELETE MUST include a bounded WHERE clause (e.g. WHERE title = :title).
                5. For UPDATE, SET ONLY the exact column(s) the user asked to change. Never include
                   updated_at, created_at, committed_at, or other audit columns — the backend manages those.
                6. For INSERT into notion.tasks: list only (title, status, priority, due_date, project).
                   Do NOT include id, user_id, or updated_at — all three are injected by the backend.
                   Use CAST(:due_date AS timestamptz) for due_date if the user provided a date.

                SOURCE SELECTION: Query ONLY tables genuinely relevant to the intent.
                - Tasks / to-dos / assignments → notion.tasks only
                - Emails / inbox / unread → gmail.emails only
                - Commits / PRs / code → github.commits and/or github.pull_requests
                - Calendar / meetings / schedule → calendar.events only
                - Overview / cross-source → whichever combination is useful
                Do not query unrelated tables — irrelevant data adds noise.

                WHERE CLAUSE EXECUTION: WHERE clauses run directly against the DB.
                Use precise filters — they hit DB indexes and reduce result sizes.
                Prefer: status, priority, is_unread, is_archived, importance (exact match)
                or committed_at, received_at, start_at, due_date (time ranges).
                Never filter by user_id — see Rule 1.

                Return ONLY JSON (no prose, no markdown fences):
                {
                  "rationale": "why these queries",
                  "operations": [{"id": "q1", "sql": "...", "bindings": {}}],
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
