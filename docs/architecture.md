# Architecture

## System Overview

The AIAC agent is a three-process system. Each process is a separate Spring Boot application with its own Maven module and JAR.

```
┌─────────────────────────────────────────────────────────┐
│                    Browser / Telegram                    │
└─────────┬──────────────────┬──────────────────┬─────────┘
          │ HTTP :80         │ Telegram API     │ curl
          ▼                  ▼                  ▼
┌─────────────────────────────────────────────────────────┐
│                Agent (port 8080)                         │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌────────────┐ │
│  │ Chat UI  │ │ RAG      │ │ TG Bot   │ │ HTTP API   │ │
│  │ Thymeleaf│ │ Pipeline │ │ Polling  │ │ /api/chat  │ │
│  └────┬─────┘ └────┬─────┘ └────┬─────┘ └─────┬──────┘ │
│       │             │            │              │        │
│       ▼             ▼            ▼              ▼        │
│  ┌──────────────────────────────────────────────────┐   │
│  │          LlmProviderSwitch                        │   │
│  │  ┌─────────────┐    ┌──────────────────────┐     │   │
│  │  │ Cerebras    │    │ OllamaQueue          │     │   │
│  │  │ (cloud)     │    │ → Ollama (local)     │     │   │
│  │  └─────────────┘    └──────────────────────┘     │   │
│  └──────────────────────────────────────────────────┘   │
│       │                                                  │
│       ▼ MCP client (SSE + streamable-HTTP)               │
└───────┬──────────────────────────────────────────────────┘
        │
   ┌────┴────────────────┬─────────────────────┐
   ▼                     ▼                     ▼
┌──────────┐      ┌──────────┐      ┌──────────────────┐
│ MCP :8081│      │ Weather  │      │ Frankfurter      │
│ Calendar │      │ :8082    │      │ (remote MCP)     │
│ Tasks    │      │ Open-    │      │ Currency rates   │
│ Notes    │      │ Meteo    │      │                  │
│ Briefing │      │          │      │                  │
│ Git      │      │          │      │                  │
└──────────┘      └──────────┘      └──────────────────┘
```

## Request Flow

### Web Chat
1. User submits message via form POST to `/`
2. ChatController builds AgentConfig from form fields
3. If RAG enabled: RagSearcher finds relevant chunks, injects into prompt
4. Agent.handle() builds the full prompt (system + memory layers + context + user message)
5. LlmClient.complete() sends to Cerebras or Ollama (via queue)
6. Response rendered in Thymeleaf template

### Telegram Bot
1. TelegramBotService polls Telegram for updates (long polling)
2. Rate limiting, blocklist, input validation
3. Message queued in internal Telegram queue
4. Worker thread calls Ollama via shared OllamaQueue
5. Response sent back to Telegram chat

### HTTP API
1. POST /api/chat with JSON body
2. ApiController validates input
3. Calls OllamaQueue.complete() (blocks until processed)
4. Returns JSON response with reply + metrics

## Data Storage

All state is in JSON files under `./data/`:
- `conversations.json` — dialogue history per visitor
- `branches.json` — conversation branch metadata
- `memory.json` — long-term memory per visitor
- `invariants.json` — standing rules
- `index/` — RAG vector index (embeddings + chunks)

## Deployment

The VPS at 2.28.124.111 runs all three processes behind nginx (port 80 → 8080). Systemd manages lifecycle. Ollama runs as a separate service for the local LLM.
