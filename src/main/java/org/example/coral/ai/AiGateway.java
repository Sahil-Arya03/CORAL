package org.example.coral.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Thin, resilient wrapper over the Spring AI ChatModel. The rest of the AI layer depends on
 * this — not on Spring AI types — so model wiring is swappable and every call degrades
 * gracefully. If no model is configured (or a call fails, e.g. missing API key), callers
 * receive Optional.empty() and fall back to deterministic heuristics, keeping the system
 * demoable offline.
 */
@Component
public class AiGateway {

    private static final Logger log = LoggerFactory.getLogger(AiGateway.class);

    private final ChatModel model;

    @Autowired
    public AiGateway(ObjectProvider<ChatModel> modelProvider) {
        this.model = modelProvider.getIfAvailable();
    }

    /** Direct wiring for tests / offline mode; pass null to force heuristic fallback everywhere. */
    public AiGateway(ChatModel model) {
        this.model = model;
    }

    public boolean available() {
        return model != null;
    }

    public Optional<String> complete(String prompt) {
        if (model == null) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(model.call(prompt));
        } catch (Exception e) {
            log.warn("AI call failed, falling back to heuristic: {}", e.toString());
            return Optional.empty();
        }
    }
}
