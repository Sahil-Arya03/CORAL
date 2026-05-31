package org.example.coral.service;

import org.example.coral.model.IntentResult;
import org.example.coral.model.ValidatedQuery;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds mutations awaiting user confirmation (the CONFIRM handshake). In-memory with a short TTL;
 * production would persist to a pending_actions table. Re-validation happens on confirm.
 */
@Service
public class PendingActionStore {

    public record Pending(String token, IntentResult intent, ValidatedQuery query,
                          int estimatedRows, Instant expiresAt,
                          long internalUserId, String clerkUserId) {
        public boolean expired() {
            return Instant.now().isAfter(expiresAt);
        }
    }

    private static final Duration TTL = Duration.ofMinutes(5);
    private final Map<String, Pending> store = new ConcurrentHashMap<>();

    public Pending create(IntentResult intent, ValidatedQuery query, int estimatedRows,
                          long internalUserId, String clerkUserId) {
        String token = UUID.randomUUID().toString();
        Pending p = new Pending(token, intent, query, estimatedRows,
                Instant.now().plus(TTL), internalUserId, clerkUserId);
        store.put(token, p);
        return p;
    }

    public Optional<Pending> consume(String token) {
        Pending p = store.remove(token);
        if (p == null || p.expired()) return Optional.empty();
        return Optional.of(p);
    }
}
