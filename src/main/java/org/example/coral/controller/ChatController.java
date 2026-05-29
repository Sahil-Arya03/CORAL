package org.example.coral.controller;

import org.example.coral.dto.ChatDtos.ActionResult;
import org.example.coral.dto.ChatDtos.ChatRequest;
import org.example.coral.dto.ChatDtos.ChatResponse;
import org.example.coral.dto.ChatDtos.ConfirmRequest;
import org.example.coral.service.OrchestrationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Thin entry point: maps DTO -> OrchestrationService -> DTO. No business logic here.
 * /chat streams the answer over SSE; /chat/sync returns it in one shot (handy for testing).
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final OrchestrationService orchestrator;
    private final ExecutorService sseExecutor = Executors.newCachedThreadPool();

    public ChatController(OrchestrationService orchestrator) {
        this.orchestrator = orchestrator;
    }

    @PostMapping("/chat/sync")
    public ChatResponse chatSync(@RequestBody ChatRequest request) {
        return orchestrator.handle(request);
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(60_000L);
        sseExecutor.submit(() -> stream(request, emitter));
        return emitter;
    }

    private void stream(ChatRequest request, SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event().name("status").data("{\"stage\":\"thinking\"}"));
            ChatResponse response = orchestrator.handle(request);
            for (String word : response.text().split(" ")) {
                emitter.send(SseEmitter.event().name("token").data(word + " "));
            }
            emitter.send(SseEmitter.event().name("final").data(response));
            emitter.complete();
        } catch (IOException e) {
            emitter.completeWithError(e);
        } catch (Exception e) {
            log.error("Chat stream failed", e);
            emitter.completeWithError(e);
        }
    }

    @PostMapping("/actions/confirm")
    public ActionResult confirm(@RequestBody ConfirmRequest request) {
        return orchestrator.confirm(request.token());
    }
}
