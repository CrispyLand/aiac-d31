package com.crispyland.telegram;

import com.crispyland.agent.llm.ChatRequest;
import com.crispyland.agent.llm.ChatResponse;
import com.crispyland.agent.llm.LlmClient;
import com.crispyland.agent.llm.LlmException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Day 30 — shared Ollama request queue.
 *
 * <p>Wraps a raw Ollama {@link LlmClient} with a bounded queue and a single worker thread.
 * Both the web UI (via {@code LlmProviderSwitch}) and the Telegram bot route through this,
 * so all Ollama requests are serialized and callers see their queue position.
 *
 * <p>When Cerebras is active in the web UI, requests bypass this entirely — only Ollama
 * calls go through the queue.
 */
public class OllamaQueue implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OllamaQueue.class);

    private final LlmClient delegate;
    private final BlockingQueue<QueuedRequest> queue;
    private final int maxQueueSize;
    private final Thread worker;
    private volatile boolean running = true;

    record QueuedRequest(ChatRequest request, CompletableFuture<ChatResponse> future) {}

    public OllamaQueue(LlmClient delegate, int maxQueueSize) {
        this.delegate = delegate;
        this.maxQueueSize = maxQueueSize;
        this.queue = new LinkedBlockingQueue<>(maxQueueSize);

        this.worker = new Thread(this::workerLoop, "ollama-queue");
        this.worker.setDaemon(true);
        this.worker.start();

        log.info("Ollama queue started (max {})", maxQueueSize);
    }

    @Override
    public ChatResponse complete(ChatRequest request) {
        CompletableFuture<ChatResponse> future = new CompletableFuture<>();
        QueuedRequest qr = new QueuedRequest(request, future);

        if (!queue.offer(qr)) {
            throw new LlmException(String.format(
                    "Ollama queue is full (%d/%d). Please try again in a moment.",
                    maxQueueSize, maxQueueSize));
        }

        int position = queue.size();
        if (position > 1) {
            log.info("Ollama request queued at position {}/{}", position, maxQueueSize);
        }

        try {
            return future.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof LlmException le) throw le;
            if (cause instanceof RuntimeException re) throw re;
            throw new LlmException("Ollama queue error: " + cause.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("Request interrupted while queued.");
        }
    }

    /** Current number of requests waiting (including the one being processed). */
    public int queueSize() {
        return queue.size();
    }

    public int maxQueueSize() {
        return maxQueueSize;
    }

    public void shutdown() {
        running = false;
        worker.interrupt();
    }

    private void workerLoop() {
        while (running) {
            try {
                QueuedRequest qr = queue.take();
                try {
                    ChatResponse response = delegate.complete(qr.request());
                    qr.future().complete(response);
                } catch (Exception e) {
                    qr.future().completeExceptionally(e);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        // Drain remaining requests on shutdown
        QueuedRequest remaining;
        while ((remaining = queue.poll()) != null) {
            remaining.future().completeExceptionally(
                    new LlmException("Ollama queue shutting down."));
        }
    }
}
