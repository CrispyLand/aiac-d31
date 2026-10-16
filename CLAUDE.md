# CLAUDE.md — aiac_day6

Project-level instructions for Claude Code. Read this before touching any file.

---

## What this project is

A multi-day AI agent challenge (one new capability per day, same codebase). The user records a short video demo after each day. The agent is a Spring Boot chat UI that talks to local MCP servers and a remote one.

**Three processes, three ports:**
| Process | Port | Artifact |
|---------|------|----------|
| Agent (chat UI) | 8080 | `target/agent-chat-0.0.1-SNAPSHOT.jar` |
| MCP server (calendar, tasks, briefing, notes) | 8081 | `mcp-server/target/mcp-server-0.0.1-SNAPSHOT.jar` |
| Weather MCP server | 8082 | `weather-server/target/weather-server-0.0.1-SNAPSHOT.jar` |

Remote MCP server: Frankfurter (`https://mcp.frankfurter.dev`) over streamable-HTTP.

---

## Tech stack

- Java 21, Spring Boot 4.1.1, Spring AI 2.0.1
- Maven (three separate `pom.xml` files — root, `mcp-server/`, `weather-server/`)
- Thymeleaf for the chat UI (`src/main/resources/templates/chat.html`)
- MCP tools use `@McpTool` / `@McpToolParam` annotations

---

## LLM provider — Cerebras

- **Endpoint:** `https://api.cerebras.ai/v1/chat/completions`
- **API key env var:** `CEREBRAS_API_KEY`
- **Available models (as of Day 20):** `gpt-oss-120b` only (no `openai/` prefix, no `gpt-oss-20b`)
- `reasoning_effort` is **not supported** — always `""`, never set it
- All background tasks (summarizer, facts extractor, compressor, invariant guard, briefing narrator, notes summarizer) use `gpt-oss-120b`

---

## Deployment

**VPS:** Hetzner, `russell@2.28.124.111`, public at `http://2.28.124.111`

**To deploy:** `./deploy.sh` from the repo root. It builds all three modules, uploads jars + credentials + profiles, installs systemd units, reloads nginx, and restarts services in order (MCP → weather → agent).

**VPS layout:**
```
/opt/aiac/
  .env                  # CEREBRAS_API_KEY, TELEGRAM_BOT_TOKEN, TELEGRAM_CHAT_ID
  credentials.json      # Google OAuth client (Desktop app)
  .google-tokens/       # Cached OAuth token — which account consented here
  agent/
    agent.jar
    data/               # conversations.json, branches.json, memory.json, invariants.json
    profiles/           # per-user personalization files
  mcp/
    mcp-server.jar
    data/               # briefings.json
  weather/
    weather-server.jar
  notes/                # files written by saveToFile tool
```

**Systemd units:** `deploy/aiac-mcp.service`, `deploy/aiac-weather.service`, `deploy/aiac-agent.service`

**MCP must start before agent** — the agent's MCP client does not reconnect if the server is down at boot.

**To switch Google account:** delete `.google-tokens/` locally, run `cd mcp-server && mvn spring-boot:run` (browser opens for consent), sign in as the desired account, then `./deploy.sh`.

---

## Google Calendar / Tasks

- OAuth scopes: `calendar.readonly` + `tasks.readonly` — read-only by construction
- Token stored in `.google-tokens/StoredCredential` (Java serialized, binary)
- `GOOGLE_TIME_ZONE=Asia/Shanghai` on VPS (set in `aiac-mcp.service`)
- **Calendar date matching:** `CalendarReader` queries a wide UTC window (day±1) and filters by the event's stored `date` string — do NOT use timezone-shifted boundaries, as the calendar's own timezone may differ from the server's
- **Tasks date matching:** `TaskReader.dueMaxFor()` uses plain date strings, not timezone-converted timestamps — this is intentional, documented in the method

---

## Agent memory layers (in prompt order)

1. **Profile** — per-user file in `profiles/`, declared not inferred, wins over everything
2. **Long-term** — keyed by visitor, survives conversation resets, max 24 entries
3. **Working memory (facts)** — key/value block, extracted every turn, max 12 facts
4. **Short-term** — compressed dialogue, keep-recent=6 messages, compress every 10
5. **Invariants** — standing rules the model may not propose; only humans write these

---

## MCP tool naming conventions

Tools exposed by the MCP servers:
- `getSchedule(date)` — Google Calendar events for a given day
- `getTasks(date?)` — Google Tasks due on or before date
- `getWeather(city?)` — current weather via Open-Meteo
- `summarize(text, instructions?)` — AI text processing (read-only, returns result)
- `saveToFile(content, filename)` — writes to `/opt/aiac/notes/`; call this AFTER `summarize` if the user asked to save
- Frankfurter tools: `get_rates`, `convert`, `list_currencies`, `list_providers`

**System prompt rule:** "if the user asked to save something to a file, you must call saveToFile — calling summarize alone is not enough."

---

## Known constraints / do not relitigate

- `spring-ai-starter-mcp-server-webmvc` does not auto-configure `RestClient.Builder` — use `RestClient.create()` directly in weather server
- MCP client `initialized: false` — keeps agent bootable when MCP server is down
- MCP client `toolcallback.enabled: false` — tools go through `McpToolBox`, not Spring AI's ChatClient
- Agent does not use Spring AI's `ChatClient` — it posts to the LLM endpoint directly via `CerebrasLlmClient`
- Context window is capped at 50000 tokens (Cerebras account limit)
- `reasoning-effort: ""` everywhere — Cerebras does not support it

---

## TODO

*(things deferred deliberately — add new items here rather than implementing speculatively)*

- Add startup log line showing which Google account the MCP server authorized (makes debugging token issues instant)
- `saveToFile` still not guaranteed to fire — model sometimes stops after `summarize`; may need stronger enforcement
- Incremental RAG reindex on deploy — use `git diff --name-only` to find changed files and call `mergeDocument()` for each, instead of re-embedding the whole codebase
- Multi-project support — namespaced RAG indexes per project, CLI detects project from working directory, web chat needs a project selector

---

## Running locally

```bash
# Terminal 1 — MCP server (opens browser for Google consent on first run)
cd mcp-server && mvn spring-boot:run

# Terminal 2 — weather server
cd weather-server && mvn spring-boot:run

# Terminal 3 — agent
CEREBRAS_API_KEY=csk-... mvn spring-boot:run
```

IntelliJ run config has `CEREBRAS_API_KEY` in its environment variables (`.idea/workspace.xml`).
