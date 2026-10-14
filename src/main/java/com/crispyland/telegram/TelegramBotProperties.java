package com.crispyland.telegram;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Day 30 — Telegram bot configuration.
 * The bot uses Ollama (local LLM) on the VPS to answer messages.
 */
@ConfigurationProperties("telegram.bot")
public record TelegramBotProperties(
        boolean enabled,
        String token,
        String model,
        List<Long> adminUserIds,
        boolean allowAll,
        int maxQueueSize,
        int rateLimitPerMinute,
        int rateLimitPerDay,
        int maxInputLength,
        int maxOutputTokens,
        int timeoutSeconds,
        List<Long> blockedUserIds,
        String systemPrompt
) {
    public TelegramBotProperties {
        if (adminUserIds == null) adminUserIds = List.of();
        if (blockedUserIds == null) blockedUserIds = List.of();
        if (maxQueueSize <= 0) maxQueueSize = 5;
        if (rateLimitPerMinute <= 0) rateLimitPerMinute = 3;
        if (rateLimitPerDay <= 0) rateLimitPerDay = 30;
        if (maxInputLength <= 0) maxInputLength = 500;
        if (maxOutputTokens <= 0) maxOutputTokens = 400;
        if (timeoutSeconds <= 0) timeoutSeconds = 120;
        if (model == null || model.isBlank()) model = "qwen2.5:0.5b";
        if (systemPrompt == null || systemPrompt.isBlank())
            systemPrompt = "You are a concise, helpful AI assistant running on a private server. "
                    + "Answer directly and keep responses short. If you don't know, say so.";
    }
}
