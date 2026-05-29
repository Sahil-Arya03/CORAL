package org.example.coral.controller;

import org.example.coral.memory.LongTermMemoryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/memory")
public class MemoryController {

    private static final long DEMO_USER_ID = 1L;

    private final LongTermMemoryService memory;

    public MemoryController(LongTermMemoryService memory) {
        this.memory = memory;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return memory.list(DEMO_USER_ID);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        memory.delete(DEMO_USER_ID, id);
        return ResponseEntity.noContent().build();
    }
}
