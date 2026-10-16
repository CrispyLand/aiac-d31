package com.crispyland.web;

import com.crispyland.agent.Agent;
import com.crispyland.agent.AgentConfig;
import com.crispyland.agent.AgentException;
import com.crispyland.agent.AgentProperties;
import com.crispyland.agent.AgentResult;
import com.crispyland.agent.Branches;
import com.crispyland.agent.ContextOverflowException;
import com.crispyland.agent.StageBlockedException;
import com.crispyland.agent.TaskPausedException;
import com.crispyland.agent.llm.LlmClient;
import com.crispyland.agent.llm.LlmProviderSwitch;
import com.crispyland.agent.invariant.Check;
import com.crispyland.agent.invariant.Invariant;
import com.crispyland.agent.invariant.InvariantKind;
import com.crispyland.agent.invariant.InvariantScope;
import com.crispyland.agent.invariant.Invariants;
import com.crispyland.agent.memory.LongTermKind;
import com.crispyland.agent.memory.LongTermStore;
import com.crispyland.agent.memory.MemoryLayer;
import com.crispyland.agent.memory.MemoryScope;
import com.crispyland.agent.memory.Message;
import com.crispyland.agent.profile.Persona;
import com.crispyland.agent.profile.PersonaSelector;
import com.crispyland.agent.profile.Profiles;
import com.crispyland.agent.task.BlockedTurn;
import com.crispyland.agent.task.PausedTurn;
import com.crispyland.agent.task.TaskStage;
import com.crispyland.agent.task.TaskState;
import com.crispyland.rag.Chunk;
import com.crispyland.rag.RagConfig;
import com.crispyland.rag.RagProperties;
import com.crispyland.rag.RagSearchResult;
import com.crispyland.rag.RagSearcher;
import com.crispyland.rag.RagState;
import com.crispyland.rag.ScoredChunk;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import com.crispyland.telegram.OllamaQueue;

/**
 * Thin by design: render the page, turn form fields into an AgentConfig, call the agent,
 * put the result on the model. No HTTP client, no JSON, and no conversation state — the
 * browser only supplies the conversation id; the agent owns the messages.
 */
@Controller
public class ChatController {

    private final Agent agent;
    private final AgentProperties properties;
    private final LlmClient llmClient;
    private final ConversationIdResolver conversationIds;
    private final Branches branches;
    private final LongTermStore longTerm;
    private final Profiles profiles;
    private final PersonaSelector personas;
    private final ActiveProfile activeProfile;
    private final RagState ragState;
    private final RagSearcher ragSearcher;
    private final RagProperties ragProperties;
    private final OllamaQueue ollamaQueue;   // nullable — only exists when Ollama is enabled

    public ChatController(Agent agent, AgentProperties properties, LlmClient llmClient,
                          ConversationIdResolver conversationIds, Branches branches,
                          LongTermStore longTerm, Profiles profiles, PersonaSelector personas,
                          ActiveProfile activeProfile,
                          RagState ragState, RagSearcher ragSearcher,
                          RagProperties ragProperties,
                          @Autowired(required = false) OllamaQueue ollamaQueue) {
        this.agent = agent;
        this.properties = properties;
        this.llmClient = llmClient;
        this.conversationIds = conversationIds;
        this.branches = branches;
        this.longTerm = longTerm;
        this.profiles = profiles;
        this.personas = personas;
        this.activeProfile = activeProfile;
        this.ollamaQueue = ollamaQueue;
        this.ragState = ragState;
        this.ragSearcher = ragSearcher;
        this.ragProperties = ragProperties;
    }

    @GetMapping("/")
    public String chatPage(Model model, HttpServletRequest request, HttpServletResponse response) {
        // Resolving here also mints the cookie for a first-time visitor, before they send anything.
        MemoryScope scope = scope(request, response);
        addBranches(model, scope.visitor());
        // No message yet, so nothing for the keyword rule to match on — this shows what the
        // visitor's default would give them, which is what the page should be honest about.
        PersonaSelector.Selection selection = select(request, null, null);
        // The profile's numbers, not the raw yml ones. The boxes are posted straight back on the
        // next turn, so prefilling them with the defaults would hand every request an explicit
        // override and silently cancel the profile's limits.
        AgentConfig config = agent.effectiveConfig(selection.persona(), null);
        model.addAttribute("form", ChatForm.of("", config, lensIdOf(selection)));
        addMemory(model, scope, agent.transcript(scope.conversation()));
        addPersona(model, request, selection);
        model.addAttribute("budget", agent.budget(scope, selection.persona(), config));
        addOptions(model, config);
        addProvider(model);
        model.addAttribute("ragEnabled", ragState.isEnabled());
        model.addAttribute("ragResult", RagSearchResult.empty(""));
        model.addAttribute("ragConfig", RagConfig.from(ragProperties));
        return "chat";
    }

    /**
     * @param whenPaused which of the two buttons on the paused notice was clicked, so the message
     *        that was refused can be sent by the same click that decides what to do about the
     *        pause: {@code resume} lifts it, {@code aside} answers with the task left frozen.
     *        One named parameter rather than two booleans, because they are two answers to one
     *        question and no click means both. Kept off {@link ChatForm} on purpose: everything
     *        in there becomes an {@code AgentConfig}, and this is a one-shot action, not a setting.
     * @param whenBlocked the same idea for the stage gate: {@code approve} moves the task out of
     *        planning and then answers, {@code anyway} answers and leaves it there. A second
     *        parameter rather than a shared one, because a click meaning "yes, the plan is agreed"
     *        must never be readable as "yes, resume the task" — they are different admissions.
     */
    @PostMapping("/")
    public String ask(@ModelAttribute("form") ChatForm form, BindingResult binding,
                      @RequestParam(required = false) String whenPaused,
                      @RequestParam(required = false) String whenBlocked,
                      @RequestParam(required = false) Integer ragCandidateK,
                      @RequestParam(required = false) Integer ragFinalK,
                      @RequestParam(required = false) Float ragSimilarityThreshold,
                      @RequestParam(required = false) Boolean ragRerankEnabled,
                      @RequestParam(required = false) Integer ragRerankScoreThreshold,
                      @RequestParam(required = false) Boolean ragQueryRewriteEnabled,
                      Model model,
                      HttpServletRequest request, HttpServletResponse response) {
        MemoryScope scope = scope(request, response);
        addBranches(model, scope.visitor());

        // Selected once and reused for the answer, the budget and the page: choosing twice would
        // let the keyword rule pick one lens for the call and a different one for the label.
        PersonaSelector.Selection selection = select(request, form.selectedLens(), form.userInput());
        Persona persona = selection.persona();
        addPersona(model, request, selection);
        // Resolved through the persona so the dropdowns show the model and effort the turn would
        // actually use, for the same reason the numeric boxes are.
        addOptions(model, agent.effectiveConfig(persona, form.toAgentConfig()));

        if (binding.hasErrors()) {
            model.addAttribute("error", "Some parameters could not be read — check the numeric fields.");
            addMemory(model, scope, agent.transcript(scope.conversation()));
            model.addAttribute("budget", agent.budget(scope, persona, properties.defaultConfig()));
            model.addAttribute("ragEnabled", ragState.isEnabled());
            model.addAttribute("ragResult", RagSearchResult.empty(form.userInput()));
            model.addAttribute("ragConfig", RagConfig.from(ragProperties));
            return "chat";
        }

        // Lifted before the turn, not after: resuming and sending are one intention, and making
        // them two clicks means the refused message has to be typed again. "resume" is spelled
        // out here rather than passed down because it changes the task, which outlives the turn;
        // an aside changes nothing, so it travels as an argument to the turn itself.
        if ("resume".equalsIgnoreCase(whenPaused)) {
            agent.resumeTask(scope);
        }

        // RAG pipeline: rewrite query → embed → initial retrieval → rerank.
        // The original question is stored in the transcript; the LLM receives the enriched prompt.
        RagConfig ragConfig = RagConfig.merge(ragCandidateK, ragFinalK, ragSimilarityThreshold,
                ragRerankEnabled, ragRerankScoreThreshold, ragQueryRewriteEnabled, ragProperties);
        RagSearchResult ragResult = RagSearchResult.empty(form.userInput());
        String effectiveInput = form.userInput();
        if (ragState.isEnabled()) {
            List<String> recentContext = agent.transcript(scope.conversation()).stream()
                    .filter(m -> m.isUser() || "assistant".equals(m.role()))
                    .map(m -> m.role() + ": " + m.content())
                    .toList();
            int contextTail = Math.min(6, recentContext.size());
            recentContext = recentContext.subList(recentContext.size() - contextTail, recentContext.size());
            ragResult = ragSearcher.search(form.userInput(), recentContext, ragConfig);
            if (!ragResult.isEmpty()) {
                effectiveInput = buildRagPrompt(ragResult.finalChunks(), form.userInput());
            } else {
                effectiveInput = buildRagEmptyPrompt(form.userInput());
            }
        }
        addProvider(model);
        model.addAttribute("ragEnabled", ragState.isEnabled());
        model.addAttribute("ragConfig", ragConfig);

        try {
            AgentResult result = agent.handle(scope, persona, form.userInput(), effectiveInput,
                    form.toAgentConfig(), PausedTurn.from(whenPaused), BlockedTurn.from(whenBlocked));
            model.addAttribute("result", result);
            model.addAttribute("ragResult", ragResult);
            addMemory(model, scope, result.transcript());
            model.addAttribute("budget", agent.budget(scope, persona, result.effectiveConfig()));
            // Keep the settings the agent actually used, but clear the box for the next turn.
            model.addAttribute("form", ChatForm.of("", result.effectiveConfig(), form.selectedLens()));
        } catch (TaskPausedException e) {
            model.addAttribute("paused", e.getMessage());
            model.addAttribute("ragResult", RagSearchResult.empty(form.userInput()));
            addMemory(model, scope, agent.transcript(scope.conversation()));
            model.addAttribute("budget", agent.budget(scope, persona, form.toAgentConfig()));
        } catch (StageBlockedException e) {
            model.addAttribute("blocked", e.getMessage());
            model.addAttribute("ragResult", RagSearchResult.empty(form.userInput()));
            addMemory(model, scope, agent.transcript(scope.conversation()));
            model.addAttribute("budget", agent.budget(scope, persona, form.toAgentConfig()));
        } catch (ContextOverflowException e) {
            model.addAttribute("error", e.getMessage());
            model.addAttribute("budget", e.budget());
            model.addAttribute("ragResult", RagSearchResult.empty(form.userInput()));
            addMemory(model, scope, agent.transcript(scope.conversation()));
        } catch (AgentException e) {
            model.addAttribute("error", e.getMessage());
            model.addAttribute("ragResult", RagSearchResult.empty(form.userInput()));
            addMemory(model, scope, agent.transcript(scope.conversation()));
            model.addAttribute("budget", agent.budget(scope, persona, form.toAgentConfig()));
        }
        return "chat";
    }

    /**
     * Discards the messages but keeps the cookie — same visitor, fresh dialogue.
     * <p>
     * Long-term memory is deliberately untouched. That is the observable difference between the
     * layers: reset and the agent still greets you by name, because the name was never part of
     * this conversation in the first place.
     */
    @PostMapping("/reset")
    public String reset(HttpServletRequest request, HttpServletResponse response) {
        // Every branch, not just the open one: a half-reset conversation with forks still
        // hanging off the transcript it no longer has is worse than either outcome.
        branches.reset(conversationIds.resolve(request, response));
        return "redirect:/";
    }

    /** Drops one long-term entry by its id, leaving the rest of the layer alone. */
    @PostMapping("/memory/forget")
    public String forget(@RequestParam String entry,
                         HttpServletRequest request, HttpServletResponse response) {
        longTerm.forget(conversationIds.resolve(request, response), entry);
        return "redirect:/";
    }

    /**
     * Erases the visitor: everything remembered about them, every branch they opened, and then
     * the id itself.
     * <p>
     * The order is the point and it is not interchangeable. Delete the data first while the id
     * still addresses it, and rotate last — rotate first and the deletes go looking under an id
     * that owns nothing, leaving the real record intact and permanently unreachable. That outcome
     * is worse than having no button, because it looks like the button worked.
     */
    @PostMapping("/memory/forget-all")
    public String forgetAll(HttpServletRequest request, HttpServletResponse response) {
        String visitor = conversationIds.resolve(request, response);
        longTerm.forgetAll(visitor);
        branches.reset(visitor);
        conversationIds.rotate(response);
        return "redirect:/";
    }

    /**
     * Ends the task in hand and keeps what it agreed: every line working memory had marked
     * {@code [agreed]} moves to long-term, and the rest of the block is discarded.
     * <p>
     * A button rather than something the model infers. The agent could be asked "is this task
     * over?" on every turn, but the answer decides whether a decision is written somewhere every
     * branch can see, and a wrong guess there is not visible and not undone by the next message.
     * Asking the person who actually knows costs one click.
     */
    @PostMapping("/task/finish")
    public String finishTask(HttpServletRequest request, HttpServletResponse response,
                             RedirectAttributes redirect) {
        Agent.TaskClosure closure = agent.finishTask(scope(request, response));
        // The refusal has to reach the page. A button that promotes nothing and says nothing is
        // indistinguishable from one that worked, which is the failure mode this whole feature
        // exists to prevent. It does not go in the error card, though: the machine declining a move
        // it was never going to allow is the feature working, and dressing that up as a fault sends
        // the reader hunting for a bug instead of reading the legal moves they were just handed.
        redirect.addFlashAttribute(closure.closed() ? "notice" : "refused", closure.why());
        return "redirect:/";
    }

    /**
     * Moves the task by hand, through the same transition table the model is held to.
     * <p>
     * Deliberately not a way around the machine. The person is trusted with <em>authority</em> —
     * they may close a task, the model may not — but an illegal move is still illegal, because a
     * table that only binds the model is not the machine's table, it is a prompt.
     */
    @PostMapping("/task/stage")
    public String moveTask(@RequestParam(required = false) String stage,
                           HttpServletRequest request, HttpServletResponse response) {
        agent.moveTask(scope(request, response), TaskStage.from(stage));
        return "redirect:/";
    }

    /**
     * Stops the task where it stands, without ending it.
     * <p>
     * The state is on disk, so this survives closing the tab and restarting the process — which is
     * the only honest way to demonstrate that resuming does not depend on anything still being in
     * the conversation. While it is paused the turn is refused outright — nothing is sent, nothing
     * is charged, and the page says so and offers the way back. An earlier version answered anyway
     * and only declined the state change, which put the refusal in the log and nowhere the user
     * would look; with no tools and no loop, answering is the only thing this agent does, so a
     * pause that still answers is not a pause.
     */
    @PostMapping("/task/pause")
    public String pauseTask(HttpServletRequest request, HttpServletResponse response) {
        agent.pauseTask(scope(request, response));
        return "redirect:/";
    }

    @PostMapping("/task/resume")
    public String resumeTask(HttpServletRequest request, HttpServletResponse response) {
        agent.resumeTask(scope(request, response));
        return "redirect:/";
    }

    /**
     * Starts a fresh task, discarding working memory without promoting any of it.
     * <p>
     * The other half of the boundary, and it has to exist: a task that went nowhere must be able
     * to end without writing its dead ends into permanent memory. Without this the only way to
     * abandon an exploration is to also keep what it concluded.
     */
    @PostMapping("/task/new")
    public String newTask(HttpServletRequest request, HttpServletResponse response) {
        agent.newTask(scope(request, response));
        return "redirect:/";
    }

    /**
     * Copies the conversation up to {@code at} onto a new branch and continues there. The parent
     * is untouched, which is the whole point: fork twice from the same message and you have two
     * futures of one past, comparable because everything before the split is identical.
     */
    @PostMapping("/branch/fork")
    public String fork(@RequestParam(required = false) String name,
                       @RequestParam(required = false, defaultValue = "-1") int at,
                       HttpServletRequest request, HttpServletResponse response) {
        branches.fork(conversationIds.resolve(request, response), name, at);
        return "redirect:/";
    }

    /** Switching is free and lossless — the other branch's messages were never touched. */
    @PostMapping("/branch/switch")
    public String switchBranch(@RequestParam(required = false) String branch,
                               HttpServletRequest request, HttpServletResponse response) {
        branches.switchTo(conversationIds.resolve(request, response), branch);
        return "redirect:/";
    }

    /**
     * Answers as somebody else from the next message on.
     * <p>
     * Memory is deliberately untouched — not reset, not partitioned. That is the demonstration:
     * the same transcript and the same remembered facts, asked the same question, come back
     * formatted completely differently. Clearing memory here would make the switch look like it
     * worked for the wrong reason.
     */
    @PostMapping("/profile/switch")
    public String switchProfile(@RequestParam(required = false) String profile,
                                HttpServletResponse response) {
        activeProfile.switchTo(profile, response);
        return "redirect:/";
    }

    /**
     * Toggles the LLM provider between Cerebras (cloud) and Ollama (local).
     * Only meaningful when Ollama is enabled — otherwise the switch is not on the page.
     */
    @PostMapping("/llm/provider")
    public String switchProvider(@RequestParam(required = false) String provider) {
        if (llmClient instanceof LlmProviderSwitch switcher) {
            LlmProviderSwitch.Provider target = "OLLAMA".equalsIgnoreCase(provider)
                    ? LlmProviderSwitch.Provider.OLLAMA
                    : LlmProviderSwitch.Provider.CEREBRAS;
            switcher.switchTo(target);
        }
        return "redirect:/";
    }

    /** Day 30 — Ollama queue status for the chat UI. */
    @GetMapping("/ollama/queue")
    @ResponseBody
    public ResponseEntity<java.util.Map<String, Object>> queueStatus() {
        if (ollamaQueue == null) {
            return ResponseEntity.ok(java.util.Map.of("enabled", false));
        }
        boolean isOllama = llmClient instanceof LlmProviderSwitch sw
                && sw.activeProvider() == LlmProviderSwitch.Provider.OLLAMA;
        return ResponseEntity.ok(java.util.Map.of(
                "enabled", true,
                "active", isOllama,
                "size", ollamaQueue.queueSize(),
                "max", ollamaQueue.maxQueueSize()));
    }

    /** Who is asking and which of their branches is open — resolved once per request. */
    private MemoryScope scope(HttpServletRequest request, HttpServletResponse response) {
        // Resolving here also mints the cookie for a first-time visitor, before they send anything.
        String visitor = conversationIds.resolve(request, response);
        return new MemoryScope(visitor, branches.activeKey(visitor));
    }

    private void addBranches(Model model, String visitor) {
        model.addAttribute("branches", branches.all(visitor));
        model.addAttribute("branch", branches.active(visitor));
    }

    /** Resolves the persona for one request: who they are, plus whichever lens applies. */
    private PersonaSelector.Selection select(HttpServletRequest request, String lens, String message) {
        return personas.select(activeProfile.current(request), lens, message);
    }

    /**
     * The profile as the page shows it — including the reason it was chosen.
     * <p>
     * The reason is on screen rather than only in the logs because a lens changes what the agent
     * refuses to do. "It would not answer that" is a bug report; "it would not answer that,
     * wearing the psychologist lens, matched on 'тревог'" is a fixable one.
     */
    private void addPersona(Model model, HttpServletRequest request,
                            PersonaSelector.Selection selection) {
        model.addAttribute("persona", selection.persona());
        model.addAttribute("personaWhy", selection.why());
        model.addAttribute("profiles", profiles.users());
        model.addAttribute("lenses", profiles.lenses());
        model.addAttribute("activeProfile", activeProfile.current(request));
    }

    private static String lensIdOf(PersonaSelector.Selection selection) {
        return selection.persona().hasLens() ? selection.lens().id() : null;
    }

    /**
     * Every layer on the page, so the claim that they are separate is something the user can see
     * rather than something the code asserts. The turn-by-turn cost series is only ever a view
     * over the transcript — never stored twice.
     */
    private void addMemory(Model model, MemoryScope scope, List<Message> transcript) {
        model.addAttribute("transcript", transcript);
        model.addAttribute("turns", TurnCost.series(transcript));
        // The stage and the moves available from it, so the buttons on the page are the transition
        // table rather than a second copy of it that can disagree.
        TaskState task = agent.task(scope.conversation());
        model.addAttribute("task", task);
        model.addAttribute("taskMoves", task.stage().moves().stream()
                .filter(stage -> stage != task.stage() && !stage.requiresHuman()).toList());
        // The head of the dialogue that no longer exists as messages — rendered above them so
        // the page shows the whole conversation, compressed part included.
        model.addAttribute("summary", agent.summary(scope.conversation()));
        model.addAttribute("facts", agent.facts(scope.conversation()));
        model.addAttribute("longTerm", agent.recall(scope.visitor()));
        // Listed on the page for the same reason they live in their own file: "what is this
        // assistant not allowed to do?" should be answerable by looking, not by testing.
        model.addAttribute("invariants", agent.invariants(scope.visitor()));
    }

    /**
     * Declares a standing rule, or amends the one already held under that id.
     * <p>
     * A form, and only a form. Every other block on this page can be written by the extractor;
     * this one cannot be reached from it at all. A model that can mint its own constraints can
     * mint the constraint that permits what it wanted to do, at which point the rules describe the
     * model's preferences rather than the user's.
     */
    @PostMapping("/invariant/declare")
    public String declareInvariant(@RequestParam(required = false) String id,
                                   @RequestParam(required = false) String kind,
                                   @RequestParam(required = false) String scope,
                                   @RequestParam(required = false) String check,
                                   @RequestParam(required = false) String rule,
                                   @RequestParam(required = false) String why,
                                   @RequestParam(required = false) String instead,
                                   @RequestParam(required = false) String watch,
                                   HttpServletRequest request, HttpServletResponse response,
                                   RedirectAttributes redirect) {
        Invariant declared = new Invariant(id, InvariantKind.from(kind), InvariantScope.from(scope),
                Check.from(check), rule, why, instead, terms(watch), true, "");
        // The rule text, not isPresent(): a rule declared from the form has no id yet, because only
        // the whole set knows which id is free. It gets one on the way in.
        if (declared.rule().isEmpty()) {
            redirect.addFlashAttribute("refused", "A rule needs something to say — nothing was declared.");
            return "redirect:/";
        }
        Invariants held = agent.declareInvariant(scope(request, response), declared);
        redirect.addFlashAttribute("notice",
                "%d rule(s) now binding.".formatted(held.binding().size()));
        return "redirect:/";
    }

    /**
     * Lifts a rule without deleting it, keeping why it stopped applying.
     * <p>
     * Retiring rather than removing because the reason is the interesting part. A rule that
     * vanishes leaves the page looking as though it was never declared, and the next person to
     * read it cannot tell a constraint that was lifted from one that was never there.
     */
    @PostMapping("/invariant/retire")
    public String retireInvariant(@RequestParam String id,
                                  @RequestParam(required = false) String reason,
                                  HttpServletRequest request, HttpServletResponse response) {
        agent.retireInvariant(scope(request, response), id,
                (reason == null || reason.isBlank()) ? "no longer applies" : reason);
        return "redirect:/";
    }

    @PostMapping("/invariant/restore")
    public String restoreInvariant(@RequestParam String id,
                                   HttpServletRequest request, HttpServletResponse response) {
        agent.restoreInvariant(scope(request, response), id);
        return "redirect:/";
    }

    private String buildRagPrompt(List<ScoredChunk> sources, String question) {
        StringBuilder sb = new StringBuilder();
        sb.append("Relevant context retrieved from the codebase:\n\n");
        for (int i = 0; i < sources.size(); i++) {
            Chunk c = sources.get(i).chunk();
            sb.append("--- [").append(i + 1).append("] ")
              .append(c.sourceFile()).append(" | ").append(c.section())
              .append(" (").append(c.chunkId()).append(") ---\n");
            sb.append(c.text()).append("\n\n");
        }
        sb.append("---\n\n");
        sb.append("User question: ").append(question).append("\n\n");
        sb.append(RAG_FORMAT_INSTRUCTION);
        return sb.toString();
    }

    private String buildRagEmptyPrompt(String question) {
        return "No relevant context was found in the codebase for this question.\n\n"
                + "User question: " + question + "\n\n"
                + "If you have tools available (e.g. git tools), try using them to answer. "
                + "Otherwise, say you don't have enough information and suggest rephrasing. "
                + "Answer naturally — do not use the ### Answer / Sources / Quotes format.";
    }

    private static final String RAG_FORMAT_INSTRUCTION = """
            Answer the question using the codebase context above and any available tools. \
            If the question asks about live state (current branch, uncommitted changes, \
            git status), use the git tools rather than describing them from the context. \
            Use citation markers like [1], [2] to reference codebase sources.

            At the end of your answer, include:

            ### Sources
            List each source you used, one per line:
            - FileName.java | section name (chunk-id)

            ### Quotes
            For each key claim, include a verbatim quote from the context above:
            > "exact words from the context" — FileName.java

            Rules:
            - Only cite sources from the provided context above. Do not invent sources.
            - Only quote text that appears word-for-word in the context. Do not paraphrase.
            - If the context does not contain enough information, say so honestly.
            - If the question is ambiguous or too vague (e.g. "the store" could refer to multiple \
            things), ask the user to clarify what they mean rather than guessing.
            - If the question is general knowledge (not specific to the codebase), answer from \
            your own knowledge and skip Sources and Quotes.
            """;

    /** One text box, comma-separated — a term per line would be a second way to say the same thing. */
    static List<String> terms(String watch) {
        if (watch == null || watch.isBlank()) {
            return List.of();
        }
        // Alternation, not a character class: \R is a line-break *sequence* and is illegal inside
        // [...], which compiles at first use rather than at startup — so it failed on the first
        // rule anyone declared and on nothing before that.
        return Arrays.stream(watch.split(",|\\R")).map(String::strip)
                .filter(term -> !term.isEmpty()).toList();
    }

    /** Provider state for the toggle on the page. */
    private void addProvider(Model model) {
        if (llmClient instanceof LlmProviderSwitch switcher) {
            model.addAttribute("llmSwitchable", true);
            model.addAttribute("llmProvider", switcher.activeProvider());
        } else {
            model.addAttribute("llmSwitchable", false);
            model.addAttribute("llmProvider", null);
        }
    }

    /** Dropdown contents come from application.yml, not from the template. */
    private void addOptions(Model model, AgentConfig config) {
        if (llmClient instanceof LlmProviderSwitch switcher
                && switcher.activeProvider() == LlmProviderSwitch.Provider.OLLAMA) {
            model.addAttribute("models", List.of(properties.ollama().model()));
        } else {
            model.addAttribute("models", properties.availableModels());
        }
        model.addAttribute("ollamaActive", llmClient instanceof LlmProviderSwitch sw
                && sw.activeProvider() == LlmProviderSwitch.Provider.OLLAMA);
        model.addAttribute("reasoningEfforts", properties.reasoningEfforts());
        model.addAttribute("layers", MemoryLayer.values());
        model.addAttribute("longTermKinds", LongTermKind.values());
        model.addAttribute("keepRecentMessages", properties.keepRecentMessages());
        model.addAttribute("maxFacts", properties.facts().maxFacts());
        model.addAttribute("invariantKinds", InvariantKind.values());
        model.addAttribute("checks", Check.values());
    }
}
