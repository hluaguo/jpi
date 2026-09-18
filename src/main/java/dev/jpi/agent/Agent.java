package dev.jpi.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

import dev.jpi.ai.AssistantMessageEventStream;
import dev.jpi.ai.Context;
import dev.jpi.ai.Message;
import dev.jpi.ai.Model;
import dev.jpi.ai.StreamFn;
import dev.jpi.ai.StreamOptions;
import dev.jpi.ai.ThinkingLevel;
import dev.jpi.ai.UserMessage;

/**
 * The stateful agent wrapper: owns the conversation state (system prompt, model,
 * thinking level, tools, messages), the listener list, and the steering/follow-up
 * queues. {@link #prompt} runs the {@link AgentLoop} synchronously on the calling
 * thread; {@link #abort} cancels from any thread.
 */
public final class Agent {

    /** How queued steering messages are drained between turns. */
    public enum SteeringMode {
        /** One steering message per turn. */
        ONE_AT_A_TIME,
        /** All queued steering messages, each turn. */
        ALL
    }

    /** How the agent is configured before the first run. */
    public static final class Builder {
        private StreamFn streamFn;
        private Model model;
        private String systemPrompt;
        private ThinkingLevel thinkingLevel = ThinkingLevel.OFF;
        private List<AgentTool> tools = List.of();
        private SteeringMode steeringMode = SteeringMode.ONE_AT_A_TIME;
        private AgentLoopConfig loopConfig;

        public Builder streamFn(StreamFn streamFn) {
            this.streamFn = streamFn;
            return this;
        }

        public Builder model(Model model) {
            this.model = model;
            return this;
        }

        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        public Builder thinkingLevel(ThinkingLevel thinkingLevel) {
            this.thinkingLevel = thinkingLevel;
            return this;
        }

        public Builder tools(List<AgentTool> tools) {
            this.tools = List.copyOf(tools);
            return this;
        }

        public Builder steeringMode(SteeringMode steeringMode) {
            this.steeringMode = steeringMode;
            return this;
        }

        /** Base loop config; steering/follow-up hooks are always wired to this agent's queues. */
        public Builder loopConfig(AgentLoopConfig loopConfig) {
            this.loopConfig = loopConfig;
            return this;
        }

        public Agent build() {
            return new Agent(this);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    private final AgentLoop loop;
    private final Queue<Message> steering = new ConcurrentLinkedQueue<>();
    private final Queue<Message> followUps = new ConcurrentLinkedQueue<>();
    private final List<Consumer<AgentEvent>> listeners = new ArrayList<>();
    private final SteeringMode steeringMode;
    private volatile CompletableFuture<Void> idle = new CompletableFuture<>();

    private String systemPrompt;
    private Model model;
    private ThinkingLevel thinkingLevel;
    private List<AgentTool> tools;
    private final List<Message> messages = new ArrayList<>();
    private volatile boolean streaming;

    private Agent(Builder builder) {
        this.systemPrompt = builder.systemPrompt;
        this.model = builder.model;
        this.thinkingLevel = builder.thinkingLevel;
        this.tools = List.copyOf(builder.tools);
        this.steeringMode = builder.steeringMode;

        AgentLoopConfig base = builder.loopConfig == null
                ? AgentLoopConfig.builder().build()
                : builder.loopConfig;
        AgentLoopConfig effective = AgentLoopConfig.builder()
                .beforeToolCall(base.beforeToolCall())
                .afterToolCall(base.afterToolCall())
                .shouldStopAfterTurn(base.shouldStopAfterTurn())
                .prepareNextTurn(base.prepareNextTurn())
                .toolExecution(base.toolExecution())
                .getSteeringMessages(this::drainSteering)
                .getFollowUpMessages(this::drainFollowUps)
                .build();
        StreamFn streamFn = builder.streamFn;
        StreamFn withThinking = (model, context, options) ->
                streamFn.stream(model, context, new StreamOptions(options.apiKey(), thinkingLevel));
        this.loop = new AgentLoop(withThinking, effective);
    }

    /** Drains queued steering messages per the configured {@link SteeringMode}. */
    private List<Message> drainSteering() {
        List<Message> drained = new ArrayList<>();
        if (steeringMode == SteeringMode.ALL) {
            Message message;
            while ((message = steering.poll()) != null) {
                drained.add(message);
            }
        } else {
            Message message = steering.poll();
            if (message != null) {
                drained.add(message);
            }
        }
        return drained;
    }

    /** Drains all queued follow-up messages. */
    private List<Message> drainFollowUps() {
        List<Message> drained = new ArrayList<>();
        Message message;
        while ((message = followUps.poll()) != null) {
            drained.add(message);
        }
        return drained;
    }

    // ------------------------------------------------------------------ state

    public String systemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    public Model model() {
        return model;
    }

    public void setModel(Model model) {
        this.model = model;
    }

    public ThinkingLevel thinkingLevel() {
        return thinkingLevel;
    }

    public void setThinkingLevel(ThinkingLevel thinkingLevel) {
        this.thinkingLevel = thinkingLevel;
    }

    public List<AgentTool> tools() {
        return tools;
    }

    public void setTools(List<AgentTool> tools) {
        this.tools = List.copyOf(tools);
    }

    /** The full conversation transcript. */
    public List<Message> messages() {
        return List.copyOf(messages);
    }

    // ------------------------------------------------------------- listeners

    /**
     * Subscribes a listener; invoked synchronously in subscription order for every
     * {@link AgentEvent}.
     *
     * @return a runnable that unsubscribes the listener
     */
    public synchronized Runnable subscribe(Consumer<AgentEvent> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    private synchronized void emit(AgentEvent event) {
        for (Consumer<AgentEvent> listener : List.copyOf(listeners)) {
            listener.accept(event);
        }
    }

    // ------------------------------------------------------------------- run

    /** Appends a user message and runs the loop until it finishes. Blocks the caller. */
    public void prompt(String text) {
        prompt(List.of(UserMessage.of(text)));
    }

    /** Appends the given messages and runs the loop until it finishes. Blocks the caller. */
    public void prompt(List<Message> prompts) {
        run(prompts);
    }

    /** Resumes the loop without appending a new user message. Blocks the caller. */
    public void continueRun() {
        run(List.of());
    }

    private void run(List<Message> prompts) {
        if (streaming) {
            throw new IllegalStateException("agent is already streaming; use steer() instead");
        }
        streaming = true;
        CompletableFuture<Void> runIdle = new CompletableFuture<>();
        idle = runIdle;
        CancellationToken token = new CancellationToken();
        currentToken = token;
        try {
            AgentContext context = new AgentContext(systemPrompt, List.copyOf(messages), tools);
            AgentLoop.LoopResult result = loop.run(model, context, prompts, token, this::emit);
            messages.addAll(result.messages());
        } finally {
            currentToken = null;
            streaming = false;
        }
        runIdle.complete(null);
    }

    /** Queues a steering message, injected after the current turn's tools finish. */
    public void steer(String text) {
        steering.add(UserMessage.of(text));
    }

    /** Queues a steering message. */
    public void steer(Message message) {
        steering.add(message);
    }

    /** Queues a follow-up message, injected only if the loop would otherwise stop. */
    public void followUp(String text) {
        followUps.add(UserMessage.of(text));
    }

    /** Queues a follow-up message. */
    public void followUp(Message message) {
        followUps.add(message);
    }

    /** Requests cancellation of the current run; safe to call from any thread. */
    public void abort() {
        // the run's token lives only in run(); expose via field
        CancellationToken token = currentToken;
        if (token != null) {
            token.abort();
        }
    }

    private volatile CancellationToken currentToken;

    /** Whether a run is currently in progress. */
    public boolean isStreaming() {
        return streaming;
    }

    /**
     * A future that completes when the current (or most recent) run has finished and
     * its {@code agent_end} listeners have settled.
     */
    public CompletableFuture<Void> waitForIdle() {
        return idle;
    }
}
