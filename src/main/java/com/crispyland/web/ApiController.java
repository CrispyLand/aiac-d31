package com.crispyland.web;

import com.crispyland.agent.llm.ChatRequest;
import com.crispyland.agent.llm.ChatResponse;
import com.crispyland.agent.llm.LlmException;
import com.crispyland.agent.memory.Message;
import com.crispyland.telegram.OllamaQueue;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Day 30 — HTTP API for the local LLM service.
 * Provides a simple chat endpoint backed by Ollama through the shared queue.
 */
@RestController
@RequestMapping("/api")
public class ApiController {

    private static final Logger log = LoggerFactory.getLogger(ApiController.class);

    private final OllamaQueue ollamaQueue;
    private final String model;

    public ApiController(@Autowired(required = false) OllamaQueue ollamaQueue,
                         @Value("${agent.ollama.model:qwen2.5:0.5b}") String model) {
        this.ollamaQueue = ollamaQueue;
        this.model = model;
    }

    public record ChatInput(String message, String model, String system,
                            Double temperature, Integer maxTokens) {}

    public record ChatOutput(String reply, String model, long latencyMs,
                             int promptTokens, int completionTokens,
                             int queueSize, int queueMax) {}

    /** POST /api/chat — send a message, get a response from the local LLM. */
    @PostMapping("/chat")
    public ResponseEntity<?> chat(@RequestBody ChatInput input) {
        if (ollamaQueue == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Ollama is not enabled on this server."));
        }
        if (input.message() == null || input.message().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Message is required."));
        }
        if (input.message().length() > 2000) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Message too long. Max 2000 characters."));
        }

        String useModel = input.model() != null && !input.model().isBlank()
                ? input.model() : model;
        String system = input.system() != null && !input.system().isBlank()
                ? input.system()
                : "You are a concise, helpful AI assistant. Answer directly.";
        double temperature = input.temperature() != null ? input.temperature() : 0.7;
        int maxTokens = input.maxTokens() != null ? input.maxTokens() : 400;

        List<Message> messages = List.of(
                Message.system(system),
                Message.user(input.message()));

        ChatRequest request = new ChatRequest(
                useModel, messages, temperature, maxTokens,
                "", List.of(), "");

        long start = System.currentTimeMillis();
        try {
            ChatResponse response = ollamaQueue.complete(request);
            long latency = System.currentTimeMillis() - start;

            log.info("API chat: {}ms, {} tokens, model={}", latency,
                    response.usage().totalTokens(), response.model());

            return ResponseEntity.ok(new ChatOutput(
                    response.content().strip(),
                    response.model(),
                    latency,
                    (int) response.usage().promptTokens(),
                    (int) response.usage().completionTokens(),
                    ollamaQueue.queueSize(),
                    ollamaQueue.maxQueueSize()));

        } catch (LlmException e) {
            long latency = System.currentTimeMillis() - start;
            log.error("API chat error after {}ms: {}", latency, e.getMessage());
            if (e.getMessage().contains("queue is full")) {
                return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                        .body(Map.of("error", e.getMessage()));
            }
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /** GET /api/status — queue and model info. */
    @GetMapping("/status")
    public ResponseEntity<?> status() {
        if (ollamaQueue == null) {
            return ResponseEntity.ok(Map.of("ollama", false));
        }
        return ResponseEntity.ok(Map.of(
                "ollama", true,
                "model", model,
                "queue", ollamaQueue.queueSize(),
                "queueMax", ollamaQueue.maxQueueSize()));
    }
}
