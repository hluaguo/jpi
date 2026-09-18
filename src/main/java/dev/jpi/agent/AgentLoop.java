package dev.jpi.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import dev.jpi.ai.AssistantMessage;
import dev.jpi.ai.AssistantMessageEvent;
import dev.jpi.ai.AssistantMessageEventStream;
import dev.jpi.ai.Content;
import dev.jpi.ai.Context;
import dev.jpi.ai.Message;
import dev.jpi.ai.Model;
import dev.jpi.ai.StopReason;
import dev.jpi.ai.StreamFn;
import dev.jpi.ai.StreamOptions;
import dev.jpi.ai.ThinkingLevel;
import dev.jpi.ai.ToolResultMessage;
import dev.jpi.util.CancellationToken;

/**
 * The pure agent loop: {@code (model, context, prompts) → events + transcript}.
 * All effects (LLM, tools) sit behind the {@link StreamFn} and {@link AgentTool}
 * interfaces; failures are data at both boundaries.
 */
public class AgentLoop {

    /** The outcome of one run: all new messages and the run's final stop reason. */
    public record LoopResult(List<Message> messages, StopReason stopReason) {

        public LoopResult {
            messages = messages == null ? List.of() : List.copyOf(messages);
        }
    }

    /** Internal pairing of a finalized tool result with its batch-level terminate flag. */
    private record ExecResult(ToolResultMessage message, boolean terminate) {
    }

    private final StreamFn streamFn;
    private final AgentLoopConfig config;

    public AgentLoop(StreamFn streamFn) {
        this(streamFn, AgentLoopConfig.builder().build());
    }

    public AgentLoop(StreamFn streamFn, AgentLoopConfig config) {
        this.streamFn = streamFn;
        this.config = config;
    }

    /** Runs the loop with no cancellation and no listener. */
    public LoopResult run(Model model, AgentContext context, List<Message> prompts) {
        return run(model, context, prompts, new CancellationToken(), null);
    }

    /** Runs the loop with no cancellation. */
    public LoopResult run(Model model, AgentContext context, List<Message> prompts, Consumer<AgentEvent> listener) {
        return run(model, context, prompts, new CancellationToken(), listener);
    }

    /**
     * Runs turns until the model stops calling tools and no queued messages remain;
     * emits lifecycle events synchronously to {@code listener}.
     */
    public LoopResult run(Model model, AgentContext context, List<Message> prompts,
                          CancellationToken signal, Consumer<AgentEvent> listener) {
        List<Message> messages = new ArrayList<>(context.messages());
        List<Message> newMessages = new ArrayList<>();
        Consumer<AgentEvent> emit = listener == null ? event -> { } : listener;

        emit.accept(new AgentEvent.Start());

        List<Message> pending = new ArrayList<>(prompts);
        pending.addAll(config.getSteeringMessages().get());
        int turnIndex = 0;
        boolean hasMoreToolCalls = true;
        StopReason stopReason = StopReason.STOP;
        Model currentModel = model;
        String currentSystemPrompt = context.systemPrompt();
        List<AgentTool> currentTools = context.tools();
        AgentLoopConfig.TurnResult lastTurn = null;

        outer:
        while (true) {
            // inner loop: turns, as long as tool calls keep coming or queued messages remain
            while (hasMoreToolCalls || !pending.isEmpty()) {
                if (lastTurn != null) {
                    AgentLoopConfig.TurnPlan plan = config.prepareNextTurn()
                            .prepareNextTurn(currentModel,
                                    new AgentContext(currentSystemPrompt, messages, currentTools), lastTurn);
                    currentModel = plan.model();
                    currentSystemPrompt = plan.context().systemPrompt();
                    currentTools = plan.context().tools();
                    messages = new ArrayList<>(plan.context().messages());
                }
                emit.accept(new AgentEvent.TurnStart(turnIndex++));
            for (Message message : pending) {
                emit.accept(new AgentEvent.MessageStart(message));
                messages.add(message);
                newMessages.add(message);
                emit.accept(new AgentEvent.MessageEnd(message));
            }
            pending = List.of();

            Context llmContext = new Context(currentSystemPrompt,
                    config.transformContext().transform(currentModel, messages),
                    currentTools.stream()
                            .map(t -> new dev.jpi.ai.Tool(t.name(), t.description(), t.parameters()))
                            .toList());
            AssistantMessage assistant = streamAssistantResponse(currentModel, llmContext, messages, newMessages, signal, emit);
            stopReason = assistant.stopReason();

            List<ToolResultMessage> turnToolResults = List.of();
            if (assistant.stopReason() == StopReason.ERROR || assistant.stopReason() == StopReason.ABORTED) {
                emit.accept(new AgentEvent.TurnEnd(assistant, turnToolResults));
                emit.accept(new AgentEvent.End(List.copyOf(newMessages)));
                return new LoopResult(List.copyOf(newMessages), assistant.stopReason());
            }

            hasMoreToolCalls = false;
            List<Content.ToolCall> toolCalls = assistant.content().stream()
                    .filter(Content.ToolCall.class::isInstance)
                    .map(Content.ToolCall.class::cast)
                    .toList();
            if (!toolCalls.isEmpty()) {
                List<ExecResult> results;
                if (assistant.stopReason() == StopReason.LENGTH) {
                    results = toolCalls.stream()
                            .map(tc -> new ExecResult(errorResult(tc,
                                    "tool call arguments may be truncated (stop_reason: length)"), false))
                            .toList();
                } else {
                    results = executeToolCalls(currentTools, toolCalls, signal, emit);
                }
                turnToolResults = results.stream().map(ExecResult::message).toList();
                hasMoreToolCalls = signal.isAborted()
                        ? false
                        : !results.stream().allMatch(ExecResult::terminate);
                for (ToolResultMessage toolResult : turnToolResults) {
                    emit.accept(new AgentEvent.MessageStart(toolResult));
                    messages.add(toolResult);
                    newMessages.add(toolResult);
                    emit.accept(new AgentEvent.MessageEnd(toolResult));
                }
            }

            lastTurn = new AgentLoopConfig.TurnResult(assistant, turnToolResults);

            emit.accept(new AgentEvent.TurnEnd(assistant, turnToolResults));
            if (signal.isAborted()) {
                emit.accept(new AgentEvent.End(List.copyOf(newMessages)));
                return new LoopResult(List.copyOf(newMessages), StopReason.ABORTED);
            }
            if (config.shouldStopAfterTurn().shouldStopAfterTurn(assistant, turnToolResults, turnIndex - 1)) {
                stopReason = assistant.stopReason();
                emit.accept(new AgentEvent.End(List.copyOf(newMessages)));
                return new LoopResult(List.copyOf(newMessages), stopReason);
            }

            pending = new ArrayList<>(config.getSteeringMessages().get());
            }

            // the loop would stop — follow-ups keep it alive
            List<Message> followUps = config.getFollowUpMessages().get();
            if (followUps.isEmpty()) {
                break outer;
            }
            pending = new ArrayList<>(followUps);
        }

        emit.accept(new AgentEvent.End(List.copyOf(newMessages)));
        return new LoopResult(List.copyOf(newMessages), stopReason);
    }

    private List<ExecResult> executeToolCalls(List<AgentTool> tools, List<Content.ToolCall> toolCalls,
                                              CancellationToken signal, Consumer<AgentEvent> emit) {
        return config.toolExecution() == AgentLoopConfig.ToolExecution.PARALLEL
                ? executeToolCallsParallel(tools, toolCalls, signal, emit)
                : executeToolCallsSequential(tools, toolCalls, signal, emit);
    }

    private List<ExecResult> executeToolCallsSequential(List<AgentTool> tools, List<Content.ToolCall> toolCalls,
                                                        CancellationToken signal, Consumer<AgentEvent> emit) {
        List<ExecResult> results = new ArrayList<>();
        for (Content.ToolCall toolCall : toolCalls) {
            if (signal.isAborted()) {
                results.add(new ExecResult(errorResult(toolCall, "Operation aborted"), false));
                continue;
            }
            results.add(executeOneToolCall(tools, toolCall, signal, emit));
        }
        return results;
    }

    /** A per-call preparation outcome for parallel execution. */
    private record Prepared(Content.ToolCall call, AgentTool tool, ExecResult error) {
        boolean executable() {
            return error == null;
        }
    }

    /**
     * Prepared sequentially so permission gates stay ordered; executed concurrently.
     * End events follow completion order (what a UI wants) while the returned results
     * keep source order (what the transcript wants).
     */
    private List<ExecResult> executeToolCallsParallel(List<AgentTool> tools, List<Content.ToolCall> toolCalls,
                                                      CancellationToken signal, Consumer<AgentEvent> emit) {
        List<Prepared> prepared = new ArrayList<>();
        for (Content.ToolCall toolCall : toolCalls) {
            if (signal.isAborted()) {
                prepared.add(new Prepared(toolCall, null, new ExecResult(errorResult(toolCall, "Operation aborted"), false)));
                continue;
            }
            AgentTool tool = tools.stream()
                    .filter(t -> t.name().equals(toolCall.name()))
                    .findFirst().orElse(null);
            if (tool == null) {
                prepared.add(new Prepared(toolCall, null,
                        new ExecResult(errorResult(toolCall, "Tool not found: " + toolCall.name()), false)));
                continue;
            }
            AgentLoopConfig.BeforeToolCallResult decision = config.beforeToolCall().beforeToolCall(tool, toolCall);
            if (decision.block()) {
                prepared.add(new Prepared(toolCall, null, new ExecResult(errorResult(toolCall, decision.reason()), false)));
                continue;
            }
            prepared.add(new Prepared(toolCall, tool, null));
        }

        // start events in source order, before any execution begins
        for (Prepared p : prepared) {
            if (p.executable()) {
                emit.accept(new AgentEvent.ToolExecutionStart(p.call().id(), p.call().name(), p.call().arguments()));
            }
        }

        Object emitLock = new Object();
        Consumer<AgentEvent> safeEmit = event -> {
            synchronized (emitLock) {
                emit.accept(event);
            }
        };
        List<CompletableFuture<ExecResult>> futures = new ArrayList<>();
        for (Prepared p : prepared) {
            if (!p.executable()) {
                futures.add(CompletableFuture.completedFuture(p.error()));
                continue;
            }
            futures.add(CompletableFuture.supplyAsync(() -> runPrepared(p, signal, safeEmit)));
        }
        List<ExecResult> results = new ArrayList<>();
        for (CompletableFuture<ExecResult> future : futures) {
            results.add(future.join());
        }
        return results;
    }

    private ExecResult runPrepared(Prepared prepared,
                                   CancellationToken signal, Consumer<AgentEvent> emit) {
        Content.ToolCall toolCall = prepared.call();
        AgentToolResult result;
        try {
            result = prepared.tool().execute(toolCall.id(), toolCall.arguments(), signal,
                    partial -> emit.accept(new AgentEvent.ToolExecutionUpdate(toolCall.id(), partial)));
        } catch (Exception e) {
            String errorMessage = e.getMessage() == null ? e.toString() : e.getMessage();
            return new ExecResult(errorResult(toolCall, errorMessage), false);
        }
        ToolResultMessage toolResult = new ToolResultMessage(
                toolCall.id(), toolCall.name(), result.content(), result.details(), false,
                System.currentTimeMillis());
        toolResult = config.afterToolCall().afterToolCall(prepared.tool(), toolCall, toolResult);
        emit.accept(new AgentEvent.ToolExecutionEnd(toolCall.id(), toolCall.name(), toolResult));
        return new ExecResult(toolResult, result.terminate());
    }

    private ExecResult executeOneToolCall(List<AgentTool> tools, Content.ToolCall toolCall,
                                          CancellationToken signal, Consumer<AgentEvent> emit) {
        AgentTool tool = tools.stream()
                .filter(t -> t.name().equals(toolCall.name()))
                .findFirst().orElse(null);
        if (tool == null) {
            return new ExecResult(errorResult(toolCall, "Tool not found: " + toolCall.name()), false);
        }

        AgentLoopConfig.BeforeToolCallResult decision =
                config.beforeToolCall().beforeToolCall(tool, toolCall);
        if (decision.block()) {
            return new ExecResult(errorResult(toolCall, decision.reason()), false);
        }

        emit.accept(new AgentEvent.ToolExecutionStart(toolCall.id(), toolCall.name(), toolCall.arguments()));
        AgentToolResult result;
        try {
            result = tool.execute(toolCall.id(), toolCall.arguments(), signal,
                    partial -> emit.accept(new AgentEvent.ToolExecutionUpdate(toolCall.id(), partial)));
        } catch (Exception e) {
            result = null;
            String errorMessage = e.getMessage() == null ? e.toString() : e.getMessage();
            return new ExecResult(errorResult(toolCall, errorMessage), false);
        }
        ToolResultMessage toolResult = new ToolResultMessage(
                toolCall.id(), toolCall.name(), result.content(), result.details(), false,
                System.currentTimeMillis());
        toolResult = config.afterToolCall().afterToolCall(tool, toolCall, toolResult);
        emit.accept(new AgentEvent.ToolExecutionEnd(toolCall.id(), toolCall.name(), toolResult));
        return new ExecResult(toolResult, result.terminate());
    }

    private static ToolResultMessage errorResult(Content.ToolCall toolCall, String errorMessage) {
        return new ToolResultMessage(
                toolCall.id(), toolCall.name(), List.of(new Content.Text("Error: " + errorMessage)),
                null, true, System.currentTimeMillis());
    }

    private AssistantMessage streamAssistantResponse(Model model, Context llmContext,
                                                     List<Message> messages, List<Message> newMessages,
                                                     CancellationToken signal, Consumer<AgentEvent> emit) {
        AssistantMessageEventStream stream = streamFn.stream(model, llmContext,
                new StreamOptions(null, ThinkingLevel.OFF, signal));
        AssistantMessage assistant = AssistantMessage.pending(model);
        for (AssistantMessageEvent event : stream) {
            if (event instanceof AssistantMessageEvent.Start) {
                emit.accept(new AgentEvent.MessageStart(assistant));
            } else if (event.isTerminal()) {
                assistant = event.partial();
                emit.accept(new AgentEvent.MessageEnd(assistant));
            } else {
                emit.accept(new AgentEvent.MessageUpdate(event));
            }
        }
        messages.add(assistant);
        newMessages.add(assistant);
        return assistant;
    }
}
