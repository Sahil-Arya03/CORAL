package org.example.coral.controller;

import org.example.coral.dto.ChatDtos;
import org.example.coral.persistence.ConversationRepository;
import org.example.coral.persistence.ThreadRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/threads")
public class ThreadController {

    private static final long DEMO_USER_ID = 1L;

    private final ThreadRepository threads;
    private final ConversationRepository conversations;

    public ThreadController(ThreadRepository threads, ConversationRepository conversations) {
        this.threads = threads;
        this.conversations = conversations;
    }

    @GetMapping
    public List<ChatDtos.ThreadDto> list() {
        return threads.list(DEMO_USER_ID);
    }

    @PostMapping
    public ResponseEntity<ChatDtos.ThreadDto> create(
            @RequestBody(required = false) ChatDtos.CreateThreadRequest req) {
        String title = (req != null && req.title() != null) ? req.title() : "New Chat";
        return threads.create(DEMO_USER_ID, title)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.status(HttpStatus.CONFLICT).build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        conversations.deleteBySession(DEMO_USER_ID, id);
        threads.delete(id, DEMO_USER_ID);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/title")
    public ResponseEntity<Void> rename(@PathVariable UUID id,
                                       @RequestBody ChatDtos.RenameThreadRequest req) {
        if (req.title() != null && !req.title().isBlank()) {
            threads.updateTitle(id, DEMO_USER_ID, req.title());
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/messages")
    public List<ChatDtos.MessageDto> messages(@PathVariable UUID id) {
        return conversations.loadHistory(DEMO_USER_ID, id);
    }
}