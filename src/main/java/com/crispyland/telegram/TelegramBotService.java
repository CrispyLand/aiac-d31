package com.crispyland.telegram;

import com.crispyland.agent.llm.ChatRequest;
import com.crispyland.agent.llm.ChatResponse;
import com.crispyland.agent.llm.LlmClient;
import com.crispyland.agent.llm.LlmException;
import com.crispyland.agent.memory.Message;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Day 30 — Telegram bot backed by a local Ollama model.
 *
 * <p>Architecture:
 * <ul>
 *   <li>Long-polling thread receives Telegram updates</li>
 *   <li>Bounded queue (max 5) serializes requests to Ollama</li>
 *   <li>Single worker thread processes the queue sequentially</li>
 *   <li>Per-user rate limiting (per minute + per day)</li>
 *   <li>Admin commands, blocklist, kill switch</li>
 * </ul>
 */
@Service
@ConditionalOnProperty(name = "telegram.bot.enabled", havingValue = "true")
@EnableConfigurationProperties(TelegramBotProperties.class)
public class TelegramBotService implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(TelegramBotService.class);
    private static final String DISCLAIMER =
            "\n\n---\n_This is a small local AI model. Answers may be inaccurate._";

    private final TelegramBotProperties props;
    private final LlmClient ollama;
    private final OllamaQueue sharedQueue;
    private final HttpClient http;
    private final ObjectMapper json = JsonMapper.builder().build();

    // Internal Telegram queue — manages Telegram-specific concerns (rate limiting, etc.)
    // The actual Ollama call goes through the shared OllamaQueue.
    private final BlockingQueue<ChatTask> tgQueue;

    // Rate limiting: userId -> deque of timestamps (minute window)
    private final Map<Long, Deque<Instant>> minuteWindows = new ConcurrentHashMap<>();
    // Rate limiting: userId -> (date, count)
    private final Map<Long, DailyCount> dailyCounts = new ConcurrentHashMap<>();

    // State
    private volatile boolean running;
    private final AtomicBoolean killed = new AtomicBoolean(false);
    private Thread pollingThread;
    private Thread workerThread;
    private long lastUpdateId = 0;

    // Stats
    private final AtomicInteger totalRequests = new AtomicInteger(0);
    private final AtomicInteger totalErrors = new AtomicInteger(0);

    record ChatTask(long chatId, String text, Instant enqueued) {}
    record DailyCount(LocalDate date, AtomicInteger count) {}

    public TelegramBotService(TelegramBotProperties props,
                              @Autowired(required = false) OllamaQueue ollamaQueue) {
        this.props = props;
        this.tgQueue = new LinkedBlockingQueue<>(props.maxQueueSize());
        this.sharedQueue = ollamaQueue;
        this.ollama = ollamaQueue != null ? ollamaQueue : createFallbackClient(props);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        log.info("Telegram bot configured: model={}, queue={} (shared={}), rate={}/min {}/day, beta={}",
                props.model(), props.maxQueueSize(), ollamaQueue != null,
                props.rateLimitPerMinute(), props.rateLimitPerDay(), !props.allowAll());
    }

    private static LlmClient createFallbackClient(TelegramBotProperties props) {
        var factory = new org.springframework.http.client.JdkClientHttpRequestFactory();
        factory.setReadTimeout(Duration.ofSeconds(props.timeoutSeconds()));
        var rest = org.springframework.web.client.RestClient.builder().requestFactory(factory).build();
        return new com.crispyland.agent.llm.CerebrasLlmClient(rest,
                JsonMapper.builder().build(),
                "http://localhost:11434/v1/chat/completions", "ollama");
    }

    // ── Lifecycle ───────────────────────────────────────────────────────────

    @Override
    public void start() {
        if (props.token() == null || props.token().isBlank()) {
            log.warn("Telegram bot enabled but no token configured — not starting");
            return;
        }
        running = true;

        pollingThread = new Thread(this::pollLoop, "tg-poll");
        pollingThread.setDaemon(true);
        pollingThread.start();

        workerThread = new Thread(this::workerLoop, "tg-worker");
        workerThread.setDaemon(true);
        workerThread.start();

        log.info("Telegram bot started (polling)");
    }

    @Override
    public void stop() {
        running = false;
        if (pollingThread != null) pollingThread.interrupt();
        if (workerThread != null) workerThread.interrupt();
        log.info("Telegram bot stopped");
    }

    @Override public boolean isRunning() { return running; }
    @Override public int getPhase() { return Integer.MAX_VALUE; } // start last, stop first

    // ── Long polling ────────────────────────────────────────────────────────

    private void pollLoop() {
        while (running) {
            try {
                String url = apiUrl("getUpdates")
                        + "?timeout=30&offset=" + (lastUpdateId + 1)
                        + "&allowed_updates=%5B%22message%22%5D";
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(35))
                        .GET().build();

                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200) {
                    log.warn("Telegram getUpdates returned {}", resp.statusCode());
                    Thread.sleep(5000);
                    continue;
                }

                JsonNode root = json.readTree(resp.body());
                if (!root.path("ok").asBoolean()) {
                    log.warn("Telegram API error: {}", root.path("description").asText());
                    Thread.sleep(5000);
                    continue;
                }

                for (JsonNode update : root.path("result")) {
                    lastUpdateId = update.path("update_id").asLong();
                    handleUpdate(update);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Polling error: {}", e.getMessage());
                try { Thread.sleep(5000); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    // ── Update handler ──────────────────────────────────────────────────────

    private void handleUpdate(JsonNode update) {
        JsonNode message = update.path("message");
        if (message.isMissingNode()) return;

        long chatId = message.path("chat").path("id").asLong();
        long userId = message.path("from").path("id").asLong();
        String username = message.path("from").path("username").asText("unknown");
        String text = message.path("text").asText("").strip();

        if (text.isEmpty()) return;

        log.info("TG message from @{} ({}): {}", username, userId,
                text.length() > 80 ? text.substring(0, 80) + "..." : text);

        // Admin commands
        if (text.startsWith("/")) {
            handleCommand(chatId, userId, username, text);
            return;
        }

        // Kill switch
        if (killed.get()) {
            sendMessage(chatId, "The bot is temporarily disabled by an admin. Try again later.");
            return;
        }

        // Access control (beta mode)
        if (!props.allowAll() && !props.adminUserIds().contains(userId)) {
            sendMessage(chatId, "This bot is in private beta. Contact the admin for access.");
            return;
        }

        // Blocklist
        if (props.blockedUserIds().contains(userId)) {
            sendMessage(chatId, "You are not allowed to use this bot.");
            return;
        }

        // Rate limiting
        String rateLimitMsg = checkRateLimit(userId);
        if (rateLimitMsg != null) {
            sendMessage(chatId, rateLimitMsg);
            return;
        }

        // Input length
        if (text.length() > props.maxInputLength()) {
            sendMessage(chatId, String.format(
                    "Message too long (%d chars). Maximum is %d characters.",
                    text.length(), props.maxInputLength()));
            return;
        }

        // Check shared Ollama queue capacity
        if (sharedQueue != null && sharedQueue.queueSize() >= sharedQueue.maxQueueSize()) {
            sendMessage(chatId, String.format(
                    "The server is busy (%d/%d in queue). Please try again in a minute.",
                    sharedQueue.maxQueueSize(), sharedQueue.maxQueueSize()));
            return;
        }

        // Enqueue into Telegram's internal queue
        totalRequests.incrementAndGet();
        ChatTask task = new ChatTask(chatId, text, Instant.now());
        if (!tgQueue.offer(task)) {
            sendMessage(chatId, String.format(
                    "Too many Telegram requests queued (%d/%d). Please try again in a minute.",
                    props.maxQueueSize(), props.maxQueueSize()));
            return;
        }

        int ollamaPos = sharedQueue != null ? sharedQueue.queueSize() + tgQueue.size() : tgQueue.size();
        if (ollamaPos > 1) {
            sendMessage(chatId, String.format(
                    "Your request is queued. Position: ~%d. Please wait...", ollamaPos));
        }
    }

    // ── Commands ────────────────────────────────────────────────────────────

    private void handleCommand(long chatId, long userId, String username, String text) {
        switch (text.split("\\s+")[0].toLowerCase()) {
            case "/start" -> sendMessage(chatId,
                    "Welcome! I'm a private AI assistant powered by a local model (" + props.model() + ").\n\n"
                    + "Send me a message and I'll do my best to help.\n\n"
                    + "Commands:\n"
                    + "/status — queue and rate limit info\n"
                    + "/help — usage tips\n\n"
                    + "_Answers come from a small AI model and may be inaccurate._");

            case "/help" -> sendMessage(chatId,
                    "Tips:\n"
                    + "- Keep messages short (max " + props.maxInputLength() + " chars)\n"
                    + "- One question at a time works best\n"
                    + "- Rate limit: " + props.rateLimitPerMinute() + "/min, "
                    + props.rateLimitPerDay() + "/day\n"
                    + "- Be patient — the model runs on a small server\n\n"
                    + "_This is a small local AI model. Answers may be wrong._");

            case "/status" -> {
                int tgSize = tgQueue.size();
                int ollamaSize = sharedQueue != null ? sharedQueue.queueSize() : 0;
                int ollamaMax = sharedQueue != null ? sharedQueue.maxQueueSize() : 0;
                sendMessage(chatId, String.format(
                        "Ollama queue: %d/%d\nTelegram queue: %d/%d\nTotal requests: %d\nErrors: %d\nBot: %s\nYour ID: %d",
                        ollamaSize, ollamaMax, tgSize, props.maxQueueSize(),
                        totalRequests.get(), totalErrors.get(),
                        killed.get() ? "DISABLED" : "running",
                        userId));
            }

            case "/kill" -> {
                if (!props.adminUserIds().contains(userId)) {
                    sendMessage(chatId, "Admin only.");
                    return;
                }
                killed.set(true);
                log.warn("Bot KILLED by admin @{} ({})", username, userId);
                sendMessage(chatId, "Bot disabled. Use /resume to re-enable.");
            }

            case "/resume" -> {
                if (!props.adminUserIds().contains(userId)) {
                    sendMessage(chatId, "Admin only.");
                    return;
                }
                killed.set(false);
                log.info("Bot RESUMED by admin @{} ({})", username, userId);
                sendMessage(chatId, "Bot re-enabled.");
            }

            default -> sendMessage(chatId, "Unknown command. Try /help");
        }
    }

    // ── Rate limiting ───────────────────────────────────────────────────────

    private String checkRateLimit(long userId) {
        Instant now = Instant.now();
        Instant oneMinuteAgo = now.minus(Duration.ofMinutes(1));

        // Per-minute check
        Deque<Instant> window = minuteWindows.computeIfAbsent(userId,
                k -> new java.util.concurrent.ConcurrentLinkedDeque<>());
        window.removeIf(t -> t.isBefore(oneMinuteAgo));
        if (window.size() >= props.rateLimitPerMinute()) {
            return String.format("Rate limit: max %d messages per minute. Please wait.",
                    props.rateLimitPerMinute());
        }

        // Per-day check
        LocalDate today = LocalDate.now();
        DailyCount dc = dailyCounts.compute(userId, (k, v) -> {
            if (v == null || !v.date().equals(today))
                return new DailyCount(today, new AtomicInteger(0));
            return v;
        });
        if (dc.count().get() >= props.rateLimitPerDay()) {
            return String.format("Daily limit reached (%d messages). Try again tomorrow.",
                    props.rateLimitPerDay());
        }

        // Record this request
        window.addLast(now);
        dc.count().incrementAndGet();
        return null;
    }

    // ── Worker (processes queue sequentially) ───────────────────────────────

    private void workerLoop() {
        while (running) {
            try {
                ChatTask task = tgQueue.take();
                processTask(task);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Worker error: {}", e.getMessage());
                totalErrors.incrementAndGet();
            }
        }
    }

    private void processTask(ChatTask task) {
        long startMs = System.currentTimeMillis();
        try {
            List<Message> messages = List.of(
                    Message.system(props.systemPrompt()),
                    Message.user(task.text()));

            ChatRequest request = new ChatRequest(
                    props.model(), messages, 0.7, props.maxOutputTokens(),
                    "", List.of(), "");

            ChatResponse response = ollama.complete(request);
            long elapsed = System.currentTimeMillis() - startMs;

            String answer = response.content().strip();
            if (answer.length() > 4000) {
                answer = answer.substring(0, 3990) + "...";
            }
            answer += DISCLAIMER;

            log.info("TG response: {}ms, {} tokens, chat={}",
                    elapsed, response.usage().totalTokens(), task.chatId());
            sendMessage(task.chatId(), answer);

        } catch (LlmException e) {
            totalErrors.incrementAndGet();
            long elapsed = System.currentTimeMillis() - startMs;
            if (elapsed > (props.timeoutSeconds() * 1000L - 5000)) {
                log.error("TG request timed out after {}ms: {}", elapsed, e.getMessage());
                sendMessage(task.chatId(),
                        "Sorry, the request timed out. The server may be busy. Try again later.");
            } else {
                log.error("TG LLM error after {}ms: {}", elapsed, e.getMessage());
                sendMessage(task.chatId(),
                        "Sorry, the AI model encountered an error. Please try again.");
            }
        } catch (Exception e) {
            totalErrors.incrementAndGet();
            log.error("TG unexpected error: {}", e.getMessage(), e);
            sendMessage(task.chatId(),
                    "Sorry, something went wrong. Please try again later.");
        }
    }

    // ── Telegram API ────────────────────────────────────────────────────────

    private void sendMessage(long chatId, String text) {
        try {
            String encoded = "chat_id=" + chatId
                    + "&text=" + URLEncoder.encode(text, StandardCharsets.UTF_8)
                    + "&parse_mode=Markdown"
                    + "&disable_web_page_preview=true";

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl("sendMessage")))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(encoded))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("sendMessage failed ({}): {}", resp.statusCode(), resp.body());
                // Retry without Markdown if formatting caused the error
                if (resp.body().contains("parse") || resp.body().contains("Markdown")) {
                    retryPlainText(chatId, text);
                }
            }
        } catch (Exception e) {
            log.error("Failed to send Telegram message: {}", e.getMessage());
        }
    }

    private void retryPlainText(long chatId, String text) {
        try {
            // Strip markdown formatting
            String plain = text.replaceAll("[_*`\\[\\]]", "");
            String encoded = "chat_id=" + chatId
                    + "&text=" + URLEncoder.encode(plain, StandardCharsets.UTF_8)
                    + "&disable_web_page_preview=true";

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl("sendMessage")))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(encoded))
                    .build();

            http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            log.error("Retry plain text also failed: {}", e.getMessage());
        }
    }

    private String apiUrl(String method) {
        return "https://api.telegram.org/bot" + props.token() + "/" + method;
    }
}
