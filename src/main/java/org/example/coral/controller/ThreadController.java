package org.example.coral.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.example.coral.config.SecurityUtils;
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

    private final ThreadRepository threads;
    private final ConversationRepository conversations;

    public ThreadController(ThreadRepository threads, ConversationRepository conversations) {
        this.threads = threads;
        this.conversations = conversations;
    }

    @GetMapping
    public List<ChatDtos.ThreadDto> list(HttpServletRequest request) {
        return threads.list(uid(request));
    }

    @PostMapping
    public ResponseEntity<ChatDtos.ThreadDto> create(
            @RequestBody(required = false) ChatDtos.CreateThreadRequest req,
            HttpServletRequest request) {
        long uid = uid(request);
        String title = (req != null && req.title() != null) ? req.title() : "New Chat";
        return threads.create(uid, title)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.status(HttpStatus.CONFLICT).build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id, HttpServletRequest request) {
        long uid = uid(request);
        conversations.deleteBySession(uid, id);
        threads.delete(id, uid);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/title")
    public ResponseEntity<Void> rename(@PathVariable UUID id,
                                       @RequestBody ChatDtos.RenameThreadRequest req,
                                       HttpServletRequest request) {
        if (req.title() != null && !req.title().isBlank()) {
            threads.updateTitle(id, uid(request), req.title());
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/messages")
    public List<ChatDtos.MessageDto> messages(@PathVariable UUID id, HttpServletRequest request) {
        return conversations.loadHistory(uid(request), id);
    }

    private static long uid(HttpServletRequest req) {
        try { return SecurityUtils.getInternalUserId(req); } catch (Exception e) { return 1L; }
    }
}