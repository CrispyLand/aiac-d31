# AIAC — AI Agent Chat

A multi-day AI agent challenge: one new capability per day, same codebase. The agent is a Spring Boot chat UI backed by cloud and local LLMs, MCP tool servers, and a RAG pipeline over its own source code.

## Architecture

Three processes, three ports:

| Process | Port | Description |
|---------|------|-------------|
| **Agent** (chat UI) | 8080 | Main app — chat, RAG, Telegram bot, HTTP API |
| **MCP server** | 8081 | Google Calendar, Tasks, briefing, notes, git tools |
| **Weather server** | 8082 | Current weather via Open-Meteo |

Remote MCP server: [Frankfurter](https://mcp.frankfurter.dev) for currency exchange rates.

## Tech Stack

- Java 21, Spring Boot 4.1.1, Spring AI 2.0.1
- Thymeleaf for the chat UI
- Ollama for local LLM (llama3.2:3b on Mac, qwen2.5:0.5b on VPS)
- Cerebras (gpt-oss-120b) for cloud LLM
- OpenAI text-embedding-3-small for embeddings
- MCP (Model Context Protocol) for tool integration

## LLM Providers

The app supports two LLM providers, switchable at runtime from the chat UI:

- **Cerebras** (cloud) — gpt-oss-120b, fast inference, default provider
- **Ollama** (local) — runs on the same machine, no external API needed

All Ollama requests go through a shared queue (max 5) — the web UI and Telegram bot are serialized.

## Agent Memory Layers

1. **Profile** — per-user file, declared preferences, wins over everything
2. **Long-term** — keyed by visitor, survives conversation resets, max 24 entries
3. **Working memory (facts)** — key/value block, extracted every turn, max 12 facts
4. **Short-term** — compressed dialogue, keep-recent=6, compress every 10 turns
5. **Invariants** — standing rules the model may not break; only humans write these

## MCP Tools

Tools available to the model when MCP is enabled:

- `getSchedule(date)` — Google Calendar events for a given day
- `getTasks(date?)` — Google Tasks due on or before date
- `getWeather(city?)` — current weather via Open-Meteo
- `summarize(text, instructions?)` — AI text processing
- `saveToFile(content, filename)` — writes to server filesystem
- `getGitBranch()` — current git branch + recent commits
- `getGitFiles(path?)` — list tracked files
- `getGitDiff(file?)` — uncommitted changes
- `getGitStatus()` — working tree status
- Frankfurter: `get_rates`, `convert`, `list_currencies`, `list_providers`

## RAG Pipeline

The RAG system indexes the entire codebase (all three modules) and answers questions about it:

1. **Indexing** — source files are chunked (fixed-size or structured), embedded, and stored
2. **Query** — user question is optionally rewritten, embedded, and matched by cosine similarity
3. **Reranking** — LLM scores candidate chunks and filters to top-k
4. **Generation** — top chunks are injected into the prompt as context

Trigger with `/help <question>` or enable RAG mode in the settings panel.

## HTTP API

```
POST /api/chat     — send a message to the local LLM
GET  /api/status   — queue and model info
```

## Telegram Bot

A Telegram bot backed by the local Ollama model. Features:
- Shared request queue with the web UI
- Rate limiting per user (3/min, 30/day)
- Beta mode (allowlist of user IDs)
- Admin commands: /kill, /resume, /status
- Input length limits, timeouts, abuse controls

## Deployment

**VPS:** Hetzner at `http://2.28.124.111`

Deploy with `./deploy.sh` from the repo root. It builds all three modules, uploads jars, installs systemd units, and restarts services in order (MCP → weather → agent).

## Running Locally

```bash
# Terminal 1 — MCP server
cd mcp-server && mvn spring-boot:run

# Terminal 2 — Weather server
cd weather-server && mvn spring-boot:run

# Terminal 3 — Agent
CEREBRAS_API_KEY=... mvn spring-boot:run
```
