package com.crispyland.agent;

import com.crispyland.briefing.BriefingClient;
import com.crispyland.telegram.OllamaQueue;
import com.crispyland.agent.judge.Judge;
import com.crispyland.agent.judge.NoOpJudge;
import com.crispyland.agent.llm.CerebrasLlmClient;
import com.crispyland.agent.llm.LlmClient;
import com.crispyland.agent.llm.LlmProviderSwitch;
import com.crispyland.agent.memory.BranchStore;
import com.crispyland.agent.memory.ConversationStore;
import com.crispyland.agent.memory.MemoryExtractor;
import com.crispyland.agent.profile.PersonaSelector;
import com.crispyland.agent.profile.Profiles;
import com.crispyland.agent.memory.HistoryCompressor;
import com.crispyland.agent.memory.InMemoryBranchStore;
import com.crispyland.agent.memory.InMemoryConversationStore;
import com.crispyland.agent.invariant.InMemoryInvariantStore;
import com.crispyland.agent.invariant.InvariantGuard;
import com.crispyland.agent.invariant.InvariantStore;
import com.crispyland.agent.invariant.JsonFileInvariantStore;
import com.crispyland.agent.memory.InMemoryLongTermStore;
import com.crispyland.agent.memory.JsonFileBranchStore;
import com.crispyland.agent.memory.JsonFileConversationStore;
import com.crispyland.agent.memory.JsonFileLongTermStore;
import com.crispyland.agent.memory.LongTermStore;
import com.crispyland.agent.policy.DefaultInputPolicy;
import com.crispyland.agent.policy.DefaultOutputPolicy;
import com.crispyland.agent.policy.InputPolicy;
import com.crispyland.agent.policy.OutputPolicy;
import com.crispyland.agent.usage.BpeTokenCounter;
import com.crispyland.agent.usage.TemplateOverhead;
import com.crispyland.agent.usage.TokenCounter;
import com.crispyland.agent.usage.TokenUsageTracker;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wires the agent's collaborators. Every one is {@code @ConditionalOnMissingBean}, so any
 * piece — client, policies, judge — can be replaced by declaring your own bean.
 */
@Configuration(proxyBeanMethods = false)
public class AgentConfiguration {

    @Bean
    @Primary
    @ConditionalOnMissingBean
    public LlmClient llmClient(AgentProperties properties,
                                @Autowired(required = false) OllamaQueue ollamaQueue) {
        ObjectMapper mapper = JsonMapper.builder().build();

        // Cerebras client (cloud)
        JdkClientHttpRequestFactory cerebrasFactory = new JdkClientHttpRequestFactory();
        cerebrasFactory.setReadTimeout(properties.readTimeout());
        RestClient cerebrasRest = RestClient.builder().requestFactory(cerebrasFactory).build();
        CerebrasLlmClient cerebrasClient = new CerebrasLlmClient(
                cerebrasRest, mapper, properties.endpoint(), properties.apiKey());

        // If Ollama is enabled, wrap both in a provider switch.
        // The Ollama client goes through OllamaQueue so web UI and Telegram bot
        // share a single serialized queue (Ollama handles one request at a time).
        if (ollamaQueue != null) {
            AgentProperties.Ollama ollama = properties.ollama();
            return new LlmProviderSwitch(cerebrasClient, ollamaQueue, ollama.model());
        }

        return cerebrasClient;
    }

    /**
     * Day 30 — shared Ollama request queue. Both the web UI (via LlmProviderSwitch) and
     * the Telegram bot route through this. Created only when Ollama is enabled.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "agent.ollama.enabled", havingValue = "true")
    public OllamaQueue ollamaQueue(AgentProperties properties) {
        AgentProperties.Ollama ollama = properties.ollama();
        ObjectMapper mapper = JsonMapper.builder().build();
        JdkClientHttpRequestFactory ollamaFactory = new JdkClientHttpRequestFactory();
        ollamaFactory.setReadTimeout(ollama.readTimeout());
        RestClient ollamaRest = RestClient.builder().requestFactory(ollamaFactory).build();
        CerebrasLlmClient ollamaClient = new CerebrasLlmClient(
                ollamaRest, mapper, ollama.endpoint(), "ollama");
        return new OllamaQueue(ollamaClient, 5);
    }

    /**
     * The briefing card's client. Two {@link RestClient}s, both built here for the same reason the
     * LLM client's is: a timeout is the only thing that stops a page render waiting on another
     * process, and it belongs next to the decision about which process that is.
     */
    @Bean
    @ConditionalOnMissingBean
    public BriefingClient briefingClient(AgentProperties properties) {
        AgentProperties.Briefing briefing = properties.briefing();
        return new BriefingClient(restClient(briefing.timeout()),
                restClient(briefing.collectTimeout()), JsonMapper.builder().build(), briefing.url());
    }

    private static RestClient restClient(java.time.Duration readTimeout) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory();
        requestFactory.setReadTimeout(readTimeout);
        return RestClient.builder().requestFactory(requestFactory).build();
    }

    @Bean
    @ConditionalOnMissingBean
    public InputPolicy inputPolicy(AgentProperties properties) {
        return new DefaultInputPolicy(properties.inputPolicy().maxLength());
    }

    @Bean
    @ConditionalOnMissingBean
    public OutputPolicy outputPolicy(AgentProperties properties) {
        return new DefaultOutputPolicy(properties.outputPolicy().maxLength());
    }

    @Bean
    @ConditionalOnMissingBean
    public Judge judge() {
        return new NoOpJudge();
    }

    @Bean
    @ConditionalOnMissingBean
    public TokenUsageTracker tokenUsageTracker() {
        return new TokenUsageTracker();
    }

    /** Loaded once — the BPE vocabulary is a few megabytes and is immutable and thread-safe. */
    @Bean
    @ConditionalOnMissingBean
    public TokenCounter tokenCounter() {
        return new BpeTokenCounter();
    }

    @Bean
    @ConditionalOnMissingBean
    public TemplateOverhead templateOverhead() {
        return new TemplateOverhead();
    }

    @Bean
    @ConditionalOnMissingBean
    public ContextPlanner contextPlanner(TokenCounter tokenCounter, TemplateOverhead templateOverhead,
                                         AgentProperties properties) {
        AgentProperties.Context context = properties.context();
        return new ContextPlanner(tokenCounter, templateOverhead, context.windows(),
                context.defaultWindow(), context.overflowPolicy(), context.warnAt());
    }

    /**
     * Summarization is an ordinary call through the same client, so it is billed, logged and
     * rate-limited exactly like a user turn — because it is one.
     */
    @Bean
    @ConditionalOnMissingBean
    public HistoryCompressor historyCompressor(LlmClient llmClient, TokenCounter tokenCounter,
                                               AgentProperties properties) {
        AgentProperties.Compression compression = properties.compression();
        String model = (compression.model() == null || compression.model().isBlank())
                ? properties.defaults().model()
                : compression.model();
        return new HistoryCompressor(llmClient, tokenCounter, model, compression.keepRecentMessages(),
                compression.compressEvery(), compression.maxSummaryTokens(),
                compression.reasoningEffort());
    }

    /**
     * Also an ordinary call through the same client — and unlike the summarizer it runs on
     * every single turn, which is the cost that makes this strategy different rather than the
     * size of the block it produces.
     * <p>
     * One extractor feeds both the working and the long-term layer, so {@code max-facts} bounds
     * the lines it may return as well as the working block it fills. A separate ceiling for the
     * reply would only ever be the smaller of the two in practice.
     */
    @Bean
    @ConditionalOnMissingBean
    public MemoryExtractor memoryExtractor(LlmClient llmClient, AgentProperties properties) {
        AgentProperties.FactMemory facts = properties.facts();
        String model = (facts.model() == null || facts.model().isBlank())
                ? properties.defaults().model()
                : facts.model();
        return new MemoryExtractor(llmClient, model, facts.maxFacts(), facts.maxTokens(),
                facts.reasoningEffort());
    }

    /**
     * The profile directory reader. Holds no state and caches nothing, so a profile edited on
     * disk takes effect on the next message rather than the next restart.
     */
    @Bean
    @ConditionalOnMissingBean
    public Profiles profiles(AgentProperties properties) {
        AgentProperties.Personalization personalization = properties.profiles();
        return new Profiles((personalization == null)
                ? java.nio.file.Path.of("./profiles") : personalization.path());
    }

    @Bean
    @ConditionalOnMissingBean
    public PersonaSelector personaSelector(Profiles profiles) {
        return new PersonaSelector(profiles);
    }

    /**
     * {@code json} keeps the dialogue across restarts; anything else forgets it on shutdown.
     */
    @Bean
    @ConditionalOnMissingBean
    public ConversationStore conversationStore(AgentProperties properties) {
        AgentProperties.Memory memory = properties.memory();
        if (AgentProperties.Memory.JSON.equalsIgnoreCase(memory.store())) {
            return new JsonFileConversationStore(JsonMapper.builder().build(),
                    Path.of(memory.file()), memory.maxMessages());
        }
        return new InMemoryConversationStore(memory.maxMessages());
    }

    /**
     * Long-term memory follows the same {@code memory.store} switch as the transcripts, but into
     * its own file. One switch because "does this deployment write to disk at all" is a single
     * question; two files because the answer to "when is this thrown away" is not.
     */
    @Bean
    @ConditionalOnMissingBean
    public LongTermStore longTermStore(AgentProperties properties) {
        AgentProperties.LongTerm longTerm = properties.longTerm();
        if (AgentProperties.Memory.JSON.equalsIgnoreCase(properties.memory().store())) {
            return new JsonFileLongTermStore(JsonMapper.builder().build(),
                    Path.of(longTerm.file()), longTerm.maxEntries());
        }
        return new InMemoryLongTermStore(longTerm.maxEntries());
    }

    /**
     * Invariants follow the same {@code memory.store} switch, into a third file.
     * <p>
     * Sharing the switch says "does this deployment write to disk at all"; keeping the file
     * separate says the rules do not expire when a conversation does. The louder reason is that
     * this is the file somebody audits.
     */
    @Bean
    @ConditionalOnMissingBean
    public InvariantStore invariantStore(AgentProperties properties) {
        if (AgentProperties.Memory.JSON.equalsIgnoreCase(properties.memory().store())) {
            return new JsonFileInvariantStore(JsonMapper.builder().build(),
                    Path.of(properties.invariants().file()));
        }
        return new InMemoryInvariantStore();
    }

    /**
     * The guard over those rules. A separate bean from the store for the usual reason — one holds
     * the rules, the other spends money deciding about them, and only one of those is worth
     * configuring a model for.
     */
    @Bean
    @ConditionalOnMissingBean
    public InvariantGuard invariantGuard(LlmClient llmClient, AgentProperties properties) {
        AgentProperties.Invariants rules = properties.invariants();
        String model = (rules.model() == null || rules.model().isBlank())
                ? properties.defaults().model() : rules.model();
        return new InvariantGuard(llmClient, model, rules.maxTokens(), rules.reasoningEffort());
    }

    /** Refs follow the transcripts: persisted together, forgotten together. */
    @Bean
    @ConditionalOnMissingBean
    public BranchStore branchStore(AgentProperties properties) {
        AgentProperties.Memory memory = properties.memory();
        if (AgentProperties.Memory.JSON.equalsIgnoreCase(memory.store())) {
            return new JsonFileBranchStore(JsonMapper.builder().build(), Path.of(memory.branchFile()));
        }
        return new InMemoryBranchStore();
    }

    @Bean
    @ConditionalOnMissingBean
    public Branches branches(BranchStore branchStore, ConversationStore conversationStore) {
        return new Branches(branchStore, conversationStore);
    }
}
