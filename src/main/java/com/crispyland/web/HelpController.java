package com.crispyland.web;

import com.crispyland.agent.AgentProperties;
import com.crispyland.agent.llm.CerebrasLlmClient;
import com.crispyland.agent.llm.ChatRequest;
import com.crispyland.agent.llm.ChatResponse;
import com.crispyland.agent.llm.LlmException;
import com.crispyland.agent.llm.ToolCall;
import com.crispyland.agent.llm.ToolResult;
import com.crispyland.agent.llm.ToolRound;
import com.crispyland.agent.llm.ToolSpec;
import com.crispyland.agent.memory.Message;
import com.crispyland.mcp.McpCatalogue;
import com.crispyland.rag.Chunk;
import com.crispyland.rag.RagConfig;
import com.crispyland.rag.RagProperties;
import com.crispyland.rag.RagSearchResult;
import com.crispyland.rag.RagSearcher;
import com.crispyland.rag.ScoredChunk;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Day 31 — Developer assistant REST endpoint.
 * Uses Cerebras gpt-oss-120b with RAG context from the codebase index
 * and MCP git tools (branch, status, diff, files) for live repo queries.
 *
 * <pre>
 *   curl -s http://localhost:8080/api/help \
 *        -H 'Content-Type: application/json' \
 *        -d '{"question":"What is the current git branch?"}'
 * </pre>
 *
 * Or use the {@code devhelp} shell script for a nicer CLI experience.
 */
@RestController
@RequestMapping("/api/help")
public class HelpController {

    private static final Logger log = LoggerFactory.getLogger(HelpController.class);
    private static final int MAX_TOOL_ROUNDS = 4;

    private static final String SYSTEM_PROMPT_BASE = """
            You are a developer assistant for the AIAC project — a Spring Boot chat agent \
            with MCP tool servers and a RAG pipeline. You have deep knowledge of this codebase.

            When answering:
            - Be concise and direct
            - Reference specific files, classes, and methods when relevant
            - If the context contains the answer, cite the source file
            - If you don't know or the context doesn't cover it, say so honestly
            - Format code snippets with ``` blocks
            - Use markdown formatting for readability
            - Do NOT hallucinate shell commands or pretend to browse files
            """;

    private static final String SYSTEM_PROMPT_WITH_TOOLS = SYSTEM_PROMPT_BASE + """

            You also have access to git tools that let you inspect the live repository. \
            Use them when the question is about the current state of the repo \
            (branch, changes, status, recent commits).
            """;

    private final CerebrasLlmClient cerebras;
    private final McpCatalogue mcp;
    private final RagSearcher ragSearcher;
    private final RagProperties ragProperties;

    public HelpController(AgentProperties agentProperties,
                          McpCatalogue mcp,
                          RagSearcher ragSearcher,
                          RagProperties ragProperties) {
        this.mcp = mcp;
        this.ragSearcher = ragSearcher;
        this.ragProperties = ragProperties;

        // Dedicated Cerebras client — not routed through the provider switch or Ollama queue.
        ObjectMapper mapper = JsonMapper.builder().build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
        factory.setReadTimeout(agentProperties.readTimeout());
        RestClient rest = RestClient.builder().requestFactory(factory).build();
        this.cerebras = new CerebrasLlmClient(
                rest, mapper, agentProperties.endpoint(), agentProperties.apiKey());
    }

    record HelpRequest(String question) {}

    record HelpResponse(String answer, String model, long latencyMs,
                        int sourcesUsed, int toolCalls,
                        long promptTokens, long completionTokens) {}

    @PostMapping
    public ResponseEntity<?> help(@RequestBody HelpRequest request) {
        if (request.question() == null || request.question().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "question is required"));
        }

        String question = request.question().strip();
        if (question.length() > 4000) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "question too long (max 4000 chars)"));
        }

        long start = System.currentTimeMillis();

        // RAG search
        RagConfig config = RagConfig.from(ragProperties);
        RagSearchResult ragResult = ragSearcher.search(question, List.of(), config);

        // Build prompt with RAG context
        String userPrompt;
        if (!ragResult.isEmpty()) {
            userPrompt = buildContextPrompt(ragResult.finalChunks(), question);
        } else {
            userPrompt = question;
        }

        try {
            // MCP tool strategy:
            // - CLI (local git context present): no MCP tools at all — local git
            //   context is already in the prompt, RAG handles code knowledge
            // - Direct API call (no local context): only git tools, not calendar/weather/etc.
            boolean hasLocalGit = question.contains("Local git state");
            Map<String, McpSyncClient> owners = new LinkedHashMap<>();
            List<ToolSpec> tools;
            if (hasLocalGit) {
                tools = List.of();
            } else {
                tools = discoverTools(owners);
                tools.removeIf(t -> !t.name().startsWith("getGit"));
                owners.keySet().removeIf(name -> !name.startsWith("getGit"));
            }

            String systemPrompt = tools.isEmpty() ? SYSTEM_PROMPT_BASE : SYSTEM_PROMPT_WITH_TOOLS;
            ChatRequest chatRequest = new ChatRequest(
                    "gpt-oss-120b",
                    List.of(Message.system(systemPrompt), Message.user(userPrompt)),
                    0.0, 2048, "", List.of(), "",
                    tools, List.of());

            ChatResponse response = cerebras.complete(chatRequest);

            // Tool loop — let the model call git tools as needed
            List<ToolRound> rounds = new ArrayList<>();
            int totalToolCalls = 0;
            while (response.wantsTools() && rounds.size() < MAX_TOOL_ROUNDS) {
                List<ToolResult> results = new ArrayList<>();
                for (ToolCall call : response.toolCalls()) {
                    log.info("/api/help tool call: {}({})", call.name(), call.arguments());
                    results.add(callTool(call, owners));
                    totalToolCalls++;
                }
                rounds.add(new ToolRound(response.toolCalls(), results));
                response = cerebras.complete(chatRequest.withRounds(rounds));
            }

            long latency = System.currentTimeMillis() - start;

            log.info("/api/help ({} ms, {} sources, {} tool calls): {}", latency,
                    ragResult.finalChunks().size(), totalToolCalls,
                    question.length() > 80 ? question.substring(0, 80) + "..." : question);

            HelpResponse result = new HelpResponse(
                    response.content(),
                    response.model(),
                    latency,
                    ragResult.finalChunks().size(),
                    totalToolCalls,
                    response.usage().promptTokens(),
                    response.usage().completionTokens());

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body(result);

        } catch (LlmException e) {
            log.error("/api/help failed: {}", e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Discovers tools from all MCP servers, ignoring the chat UI's master switch.
     * Populates the owners map so {@link #callTool} knows which client to route to.
     */
    private List<ToolSpec> discoverTools(Map<String, McpSyncClient> owners) {
        List<ToolSpec> specs = new ArrayList<>();
        for (McpSyncClient client : mcp.allConnections()) {
            String server = McpCatalogue.label(client);
            try {
                if (!client.isInitialized()) {
                    client.initialize();
                }
                for (McpSchema.Tool tool : client.listTools().tools()) {
                    if (owners.putIfAbsent(tool.name(), client) == null) {
                        specs.add(new ToolSpec(tool.name(),
                                tool.description() != null ? tool.description() : "",
                                tool.inputSchema()));
                    }
                }
            } catch (Exception e) {
                log.warn("/api/help: MCP server '{}' unavailable: {}", server, e.toString());
            }
        }
        return specs;
    }

    private ToolResult callTool(ToolCall call, Map<String, McpSyncClient> owners) {
        McpSyncClient client = owners.get(call.name());
        if (client == null) {
            return ToolResult.failed(call, "No server offers tool '" + call.name() + "'");
        }
        try {
            McpSchema.CallToolResult result = client.callTool(
                    McpSchema.CallToolRequest.builder()
                            .name(call.name())
                            .arguments(call.arguments())
                            .build());
            StringBuilder text = new StringBuilder();
            for (McpSchema.Content content : result.content()) {
                if (content instanceof McpSchema.TextContent block && block.text() != null) {
                    if (!text.isEmpty()) text.append('\n');
                    text.append(block.text());
                }
            }
            String output = text.isEmpty() ? "(empty)" : text.toString();
            return Boolean.TRUE.equals(result.isError())
                    ? ToolResult.failed(call, output)
                    : ToolResult.of(call, output);
        } catch (Exception e) {
            log.warn("/api/help: tool '{}' failed: {}", call.name(), e.toString());
            return ToolResult.failed(call, "Tool failed: " + e.getMessage());
        }
    }

    private String buildContextPrompt(List<ScoredChunk> sources, String question) {
        StringBuilder sb = new StringBuilder();
        sb.append("Relevant context from the codebase:\n\n");
        for (int i = 0; i < sources.size(); i++) {
            Chunk c = sources.get(i).chunk();
            sb.append("--- [").append(i + 1).append("] ")
              .append(c.sourceFile()).append(" | ").append(c.section())
              .append(" ---\n");
            sb.append(c.text()).append("\n\n");
        }
        sb.append("---\n\nQuestion: ").append(question);
        return sb.toString();
    }
}
