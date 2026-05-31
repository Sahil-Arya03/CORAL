package org.example.coral.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.example.coral.config.SecurityUtils;
import org.example.coral.memory.LongTermMemoryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/memory")
public class MemoryController {

    private final LongTermMemoryService memory;

    public MemoryController(LongTermMemoryService memory) {
        this.memory = memory;
    }

    @GetMapping
    public List<Map<String, Object>> list(HttpServletRequest request) {
        return memory.list(uid(request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id, HttpServletRequest request) {
        memory.delete(uid(request), id);
        return ResponseEntity.noContent().build();
    }

    private static long uid(HttpServletRequest req) {
        try { return SecurityUtils.getInternalUserId(req); } catch (Exception e) { return 1L; }
    }
}