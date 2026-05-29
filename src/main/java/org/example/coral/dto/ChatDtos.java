package org.example.coral.dto;

import java.util.List;
import java.util.UUID;

/**
 * Request/response DTOs for the /chat endpoint and action confirmation.
 */
public final class ChatDtos {

    private ChatDtos() {}

    public record ChatRequest(String prompt, UUID sessionId) {}

    public record Insight(String title, String body, String source) {}

    public record Recommendation(String label, String actionType, String confirmToken) {}

    /** Returned when a mutation needs explicit user confirmation before execution. */
    public record PendingAction(String token, String description, int affectedRows, String actionType) {}

    public record ChatResponse(
            String text,
            List<Insight> insights,
            List<Recommendation> actions,
            PendingAction pending
    ) {
        public static ChatResponse text(String text) {
            return new ChatResponse(text, List.of(), List.of(), null);
        }
    }

    public record ConfirmRequest(String token) {}

    public record ActionResult(boolean executed, String message, int rowsAffected) {}

    // ── Thread management ──────────────────────────────────────────────────────

    public record ThreadDto(UUID id, String title, String updatedAt, int messageCount) {}

    public record CreateThreadRequest(String title) {}

    public record RenameThreadRequest(String title) {}

    public record MessageDto(String role, String content, String createdAt) {}
}
