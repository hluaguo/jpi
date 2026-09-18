package dev.jpi.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

import dev.jpi.ai.AssistantMessage;
import dev.jpi.ai.AssistantMessageEventStream;
import dev.jpi.ai.Context;
import dev.jpi.ai.Message;
import dev.jpi.ai.Model;
import dev.jpi.ai.StreamFn;
import dev.jpi.ai.StreamOptions;
import dev.jpi.ai.ThinkingLevel;
import dev.jpi.ai.UserMessage;
import dev.jpi.util.CancellationToken;

/**
 * The stateful agent wrapper: owns the conversation state (system prompt, model,
 * thinking level, tools, messages), the listener list, and the steering/follow-up
 * queues. {@link #prompt} runs the {@link AgentLoop} synchronously on the calling
 * thread; {@link #abort} cancels from any thread.
 */
public final class Agent {

    /** How queued messages in a queue are drained. Shared by both queues. */
    public enum QueueMode {
        /** One message per injection point. */
        ONE_AT_A_TIME,
        /** All queued messages, each injection point. */
        ALL
    }

    /** How the agent is configured before the first run. */
    public static final class Builder {
        private StreamFn streamFn;
        private Model model;
        private String systemPrompt;
        private ThinkingLevel thinkingLevel = ThinkingLevel.OFF;
        private List<AgentTool> tools = List.of();
        private QueueMode steeringMode = QueueMode.ONE_AT_A_TIME;
        private QueueMode followUpMode = QueueMode.ONE_AT_A_TIME;
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

        public Builder steeringMode(QueueMode steeringMode) {
            this.steeringMode = steeringMode;
            return this;
        }

        /** How the follow-up queue drains when the loop would stop. */
        public Builder followUpMode(QueueMode followUpMode) {
            this.followUpMode = followUpMode;
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
    private final QueueMode steeringMode;
    private final QueueMode followUpMode;
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
        this.followUpMode = builder.followUpMode;

        AgentLoopConfig base = builder.loopConfig == null
                ? AgentLoopConfig.builder().build()
                : builder.loopConfig;
        AgentLoopConfig effective = AgentLoopConfig.builder()
                .beforeToolCall(base.beforeToolCall())
                .afterToolCall(base.afterToolCall())
                .shouldStopAfterTurn(base.shouldStopAfterTurn())
                .prepareNextTurn(base.prepareNextTurn())
                .transformContext(base.transformContext())
                .toolExecution(base.toolExecution())
                .getSteeringMessages(this::drainSteering)
                .getFollowUpMessages(this::drainFollowUps)
                .build();
        StreamFn streamFn = builder.streamFn;
        StreamFn withThinking = (model, context, options) ->
                streamFn.stream(model, context, new StreamOptions(options.apiKey(), thinkingLevel, options.cancel()));
        this.loop = new AgentLoop(withThinking, effective);
    }

    /** Drains queued steering messages per the configured {@link QueueMode}. */
    private List<Message> drainSteering() {
        List<Message> drained = new ArrayList<>();
        if (steeringMode == QueueMode.ALL) {
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

    /** Drains queued follow-up messages per the configured follow-up {@link QueueMode}. */
    private List<Message> drainFollowUps() {
        List<Message> drained = new ArrayList<>();
        if (followUpMode == QueueMode.ALL) {
            Message message;
            while ((message = followUps.poll()) != null) {
                drained.add(message);
            }
        } else {
            Message message = followUps.poll();
            if (message != null) {
                drained.add(message);
            }
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
     * Subscribes a listener; events arrive synchronously in subscription order, on
     * whatever thread the loop is running on.
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

    /** Appends a user message and runs the loop; blocks the caller until the run ends. */
    public void prompt(String text) {
        prompt(List.of(UserMessage.of(text)));
    }

    /** Appends the given messages and runs the loop; blocks the caller. */
    public void prompt(List<Message> prompts) {
        run(prompts);
    }

    /**
     * Resumes the loop without appending a new user message; blocks the caller.
     *
     * <p>Rejected when the transcript is empty or ends with an assistant message:
     * providers reject a conversation that does not end in a user or tool result
     * message, so continuing from there just buys a wire error. Continue is the
     * retry path — e.g. from an aborted tool batch — not a general re-prompt.
     */
    public void continueRun() {
        if (messages.isEmpty()) {
            throw new IllegalStateException("Cannot continue: no messages in context");
        }
        if (messages.get(messages.size() - 1) instanceof AssistantMessage) {
            throw new IllegalStateException("Cannot continue from message role: assistant");
        }
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

    public void steer(Message message) {
        steering.add(message);
    }

    /** Queues a follow-up message, injected only if the loop would otherwise stop. */
    public void followUp(String text) {
        followUps.add(UserMessage.of(text));
    }

    public void followUp(Message message) {
        followUps.add(message);
    }

    /** How the follow-up queue drains when the loop would stop. */
    public QueueMode followUpMode() {
        return followUpMode;
    }

    /** Whether any steering or follow-up message is queued. */
    public boolean hasQueuedMessages() {
        return !steering.isEmpty() || !followUps.isEmpty();
    }

    /** Drops all queued steering messages. */
    public void clearSteeringQueue() {
        steering.clear();
    }

    /** Drops all queued follow-up messages. */
    public void clearFollowUpQueue() {
        followUps.clear();
    }

    /** Drops all queued steering and follow-up messages. */
    public void clearAllQueues() {
        clearSteeringQueue();
        clearFollowUpQueue();
    }

    /**
     * Clears the transcript and both queues for a fresh conversation (a UI "new
     * chat"). Rejected while a run is in progress — abort first.
     */
    public void reset() {
        if (streaming) {
            throw new IllegalStateException("agent is streaming; abort() and wait for idle before reset()");
        }
        messages.clear();
        clearAllQueues();
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
     * A future that completes only after the run ended <em>and</em> its {@code
     * agent_end} listeners have returned — callers that must not race the UI use this
     * rather than watching {@link #isStreaming}.
     */
    public CompletableFuture<Void> waitForIdle() {
        return idle;
    }
}
