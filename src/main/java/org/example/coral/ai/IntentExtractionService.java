package org.example.coral.ai;

import org.example.coral.model.ActionClass;
import org.example.coral.model.IntentResult;
import org.example.coral.security.PolicyEngine;
import org.example.coral.util.Json;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * AI stage 1: turn the user's message into structured intent. Falls back to a keyword heuristic
 * when the model is unavailable or returns unparseable output, so the pipeline always proceeds.
 */
@Service
public class IntentExtractionService {

    private final AiGateway ai;
    private final PromptBuilderService prompts;
    private final PolicyEngine policy;

    public IntentExtractionService(AiGateway ai, PromptBuilderService prompts, PolicyEngine policy) {
        this.ai = ai;
        this.prompts = prompts;
        this.policy = policy;
    }

    public IntentResult extract(String userPrompt) {
        return ai.complete(prompts.intentPrompt(userPrompt, policy.knownActions()))
                .flatMap(raw -> Json.parse(raw, IntentResult.class))
                .filter(i -> i.actionClass() != null)
                .orElseGet(() -> heuristic(userPrompt));
    }

    /** Deterministic fallback — keyword classification over the user's words. */
    IntentResult heuristic(String userPrompt) {
        String p = userPrompt == null ? "" : userPrompt.toLowerCase(Locale.ROOT);

        if (matchesAny(p, "hi", "hello", "hey", "thanks", "thank you")) {
            return new IntentResult("greeting", ActionClass.SMALLTALK, null,
                    List.of(), List.of(), null, null);
        }

        List<String> sources = new ArrayList<>();
        List<String> tags = new ArrayList<>();
        if (matchesAny(p, "task", "todo", "assignment", "notion")) { sources.add("notion.tasks"); tags.add("tasks"); }
        if (matchesAny(p, "commit", "github", "pull request", "pr", "code")) { sources.add("github.commits"); tags.add("coding"); }
        if (matchesAny(p, "email", "mail", "gmail", "inbox")) { sources.add("gmail.emails"); tags.add("email"); }
        if (matchesAny(p, "meeting", "calendar", "event", "schedule")) { sources.add("calendar.events"); tags.add("calendar"); }
        if (sources.isEmpty()) { sources.add("notion.tasks"); sources.add("gmail.emails"); tags.add("overview"); }

        // ── Detect create intent first (before generic mutate check) ─────────────
        boolean create = matchesAny(p, "create", "add", "new task", "remind me to", "make a task");
        if (create && sources.contains("notion.tasks")) {
            return new IntentResult("create task", ActionClass.MUTATE, "task.create",
                    sources, tags, extractEntity(userPrompt), null);
        }

        boolean mutate = matchesAny(p, "mark", "complete", "completed", "done", "finish",
                "delete", "remove", "archive", "unarchive", "set ", "update", "change",
                "rename", "move", "read");
        if (mutate) {
            String actionType;
            if (matchesAny(p, "delete", "remove") && sources.contains("notion.tasks"))
                actionType = "task.delete";
            else if (matchesAny(p, "delete", "remove"))
                actionType = "reminder.delete_dup";
            else if (matchesAny(p, "unarchive"))
                actionType = "reminder.unarchive";
            else if (matchesAny(p, "archive"))
                actionType = "reminder.archive";
            else if (matchesAny(p, "mark read", "read"))
                actionType = "reminder.mark_read";
            else if (matchesAny(p, "rename", "title"))
                actionType = "task.update_title";
            else if (matchesAny(p, "project", "move", "category"))
                actionType = "task.update_project";
            else if (matchesAny(p, "deadline", "due"))
                actionType = "task.update_deadline";
            else if (matchesAny(p, "priority"))
                actionType = "task.update_priority";
            else
                actionType = "task.update_status";
            return new IntentResult("mutation request", ActionClass.MUTATE, actionType,
                    sources, tags, extractEntity(userPrompt),
                    matchesAny(p, "done", "complete") ? "done" : null);
        }

        if (matchesAny(p, "productiv", "focus", "how am i", "reflect", "summary of my")) {
            return new IntentResult("reflection/productivity", ActionClass.REFLECT, null, sources, tags, null, null);
        }

        return new IntentResult("read request", ActionClass.READ, null, sources, tags, null, null);
    }

    private boolean matchesAny(String haystack, String... needles) {
        for (String n : needles) {
            if (haystack.contains(n)) return true;
        }
        return false;
    }

    private String extractEntity(String userPrompt) {
        // Best-effort: the noun phrase the user is acting on; the AI path does this far better.
        return userPrompt;
    }
}
