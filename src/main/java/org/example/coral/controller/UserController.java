package org.example.coral.controller;

import org.example.coral.persistence.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserRepository users;

    public UserController(UserRepository users) {
        this.users = users;
    }

    /**
     * Called by the frontend immediately after Clerk reports a successful
     * sign-in or sign-up. Creates the user row on first visit; updates
     * email/username on subsequent visits if they changed in Clerk.
     *
     * No password is received or stored — Clerk manages auth credentials.
     */
    @PostMapping("/sync")
    public ResponseEntity<SyncResponse> sync(@RequestBody SyncRequest req) {
        if (req.clerkId() == null || req.clerkId().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        long id = users.upsert(
                req.clerkId(),
                req.email()       == null ? "" : req.email(),
                req.username()    == null ? "" : req.username(),
                req.displayName() == null ? "" : req.displayName()
        );
        if (id < 0) return ResponseEntity.internalServerError().build();
        return ResponseEntity.ok(new SyncResponse(id, req.email(), req.username()));
    }

    public record SyncRequest(String clerkId, String email, String username, String displayName) {}
    public record SyncResponse(long id, String email, String username) {}
}
