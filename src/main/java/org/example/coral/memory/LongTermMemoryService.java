package org.example.coral.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.coral.ai.AiGateway;
import org.example.coral.ai.PromptBuilderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Selectively extracts important personal facts from chat exchanges and persists them to
 * creatoros.user_memory as long-term context. Runs asynchronously so it never blocks responses.
 * Only extracts from READ/REFLECT paths; skips short messages and low-importance facts.
 */
@Service
public class LongTermMemoryService {

    private static final Logger log = LoggerFactory.getLogger(LongTermMemoryService.class);
    private static final int MIN_WORD_COUNT = 10;
    private static final int MIN_IMPORTANCE = 3;

    private final AiGateway ai;
    private final PromptBuilderService prompts;
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    public LongTermMemoryService(AiGateway ai, PromptBuilderService prompts,
                                 NamedParameterJdbcTemplate jdbc) {
        this.ai = ai;
        this.prompts = prompts;
        this.jdbc = jdbc;
    }

    /** Fire-and-forget extraction after each READ/REFLECT exchange. */
    public void extractAndStoreAsync(long userId, String userMessage, String assistantResponse) {
        if (userMessage == null || userMessage.split("\\s+").length < MIN_WORD_COUNT) return;
        CompletableFuture.runAsync(() -> extract(userId, userMessage, assistantResponse));
    }

    public List<Map<String, Object>> list(long userId) {
        try {
            return jdbc.queryForList("""
                    SELECT id, category, content, importance, created_at::text AS created_at
                    FROM creatoros.user_memory
                    WHERE user_id = :uid
                    ORDER BY importance DESC NULLS LAST, created_at DESC
                    """, new MapSqlParameterSource("uid", userId));
        } catch (Exception e) {
            log.warn("memory list failed: {}", e.getMessage());
            return List.of();
        }
    }

    public void delete(long userId, long memoryId) {
        try {
            jdbc.update(
                    "DELETE FROM creatoros.user_memory WHERE id = :id AND user_id = :uid",
                    new MapSqlParameterSource().addValue("id", memoryId).addValue("uid", userId));
        } catch (Exception e) {
            log.warn("memory delete failed: {}", e.getMessage());
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private void extract(long userId, String userMessage, String assistantResponse) {
        try {
            Optional<String> result = ai.complete(
                    prompts.memoryExtractionPrompt(userMessage, assistantResponse));
            if (result.isEmpty()) return;

            String json = stripFences(result.get().trim());
            Map<String, Object> parsed = mapper.readValue(json, new TypeReference<>() {});
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> memories =
                    (List<Map<String, Object>>) parsed.getOrDefault("memories", List.of());

            for (Map<String, Object> mem : memories) {
                String content = (String) mem.get("content");
                if (content == null || content.isBlank()) continue;
                int importance = mem.get("importance") instanceof Number n ? n.intValue() : 2;
                if (importance < MIN_IMPORTANCE) continue;
                if (isDuplicate(userId, content)) continue;

                String category = mem.getOrDefault("category", "observation").toString();
                @SuppressWarnings("unchecked")
                List<String> tags = mem.get("tags") instanceof List<?> t
                        ? t.stream().map(Object::toString).toList()
                        : List.of();
                store(userId, content, category, tags, importance);
            }
        } catch (Exception e) {
            log.debug("memory extraction skipped: {}", e.getMessage());
        }
    }

    private boolean isDuplicate(long userId, String content) {
        try {
            String prefix = content.length() > 80 ? content.substring(0, 80) : content;
            Integer n = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM creatoros.user_memory
                    WHERE user_id = :uid AND content ILIKE :match
                    """,
                    new MapSqlParameterSource()
                            .addValue("uid", userId)
                            .addValue("match", "%" + prefix.replace("%", "\\%") + "%"),
                    Integer.class);
            return n != null && n > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void store(long userId, String content, String category,
                       List<String> tags, int importance) {
        try {
            String tagsLiteral = tags.isEmpty() ? "{}" :
                    "{" + String.join(",", tags.stream()
                            .map(t -> "\"" + t.replace("\"", "") + "\"")
                            .toList()) + "}";
            jdbc.update("""
                    INSERT INTO creatoros.user_memory
                        (user_id, category, content, tags, importance, confidence)
                    VALUES (:uid, :cat, :content, CAST(:tags AS text[]), :imp, :conf)
                    """,
                    new MapSqlParameterSource()
                            .addValue("uid", userId)
                            .addValue("cat", category)
                            .addValue("content", content)
                            .addValue("tags", tagsLiteral)
                            .addValue("imp", (short) importance)
                            .addValue("conf", 0.8f));
        } catch (Exception e) {
            log.warn("memory store failed: {}", e.getMessage());
        }
    }

    private static String stripFences(String s) {
        if (s.startsWith("```")) {
            s = s.replaceAll("^```[a-z]*\\n?", "").replaceAll("```\\s*$", "").trim();
        }
        return s;
    }
}
