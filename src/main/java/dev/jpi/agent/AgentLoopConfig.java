package dev.jpi.agent;

import dev.jpi.ai.Content;
import dev.jpi.ai.AssistantMessage;
import dev.jpi.ai.Message;
import dev.jpi.ai.Model;
import dev.jpi.ai.ToolResultMessage;

import java.util.List;

/**
 * Loop configuration: hook points instead of features. Permissions = {@code beforeToolCall};
 * every hook has a pass-through default.
 */
public final class AgentLoopConfig {

    /** Decides whether a tool call may execute; a block becomes an error tool result. */
    public interface BeforeToolCallHook {
        BeforeToolCallResult beforeToolCall(AgentTool tool, Content.ToolCall call);
    }

    /** The hook's decision. */
    public record BeforeToolCallResult(boolean block, String reason) {

        public static BeforeToolCallResult allow() {
            return new BeforeToolCallResult(false, null);
        }

        public static BeforeToolCallResult block(String reason) {
            return new BeforeToolCallResult(true, reason);
        }
    }

    /** Field-wise override of a finalized tool result, before it is emitted or appended. */
    public interface AfterToolCallHook {
        ToolResultMessage afterToolCall(AgentTool tool, Content.ToolCall call, ToolResultMessage result);
    }

    /** Queued messages injected between turns (typed while the agent was busy). */
    public interface SteeringHook extends java.util.function.Supplier<List<Message>> {
    }

    /** Follow-up queue: polled when the loop would stop; non-empty keeps it alive. */
    public interface FollowUpHook extends java.util.function.Supplier<List<Message>> {
    }

    /** Decides, after each turn, whether the run should end. */
    public interface ShouldStopAfterTurnHook {
        boolean shouldStopAfterTurn(AssistantMessage assistant, List<ToolResultMessage> toolResults, int turnIndex);
    }

    /** A completed turn, as handed to {@link PrepareNextTurnHook}. */
    public record TurnResult(AssistantMessage assistant, List<ToolResultMessage> toolResults) {
    }

    /** What the next turn should run with; both fields are optional overrides. */
    public record TurnPlan(Model model, AgentContext context) {
    }

    /** May swap the model and/or context before every turn after the first (e.g. compaction). */
    public interface PrepareNextTurnHook {
        TurnPlan prepareNextTurn(Model model, AgentContext context, TurnResult lastTurn);
    }

    /** How a batch of tool calls is executed. */
    public enum ToolExecution {
        /** One at a time, in source order; abort checked between calls. */
        SEQUENTIAL,
        /** Prepared sequentially, executed concurrently; ends emitted in completion order. */
        PARALLEL
    }

    private final BeforeToolCallHook beforeToolCall;
    private final AfterToolCallHook afterToolCall;
    private final SteeringHook getSteeringMessages;
    private final FollowUpHook getFollowUpMessages;
    private final ShouldStopAfterTurnHook shouldStopAfterTurn;
    private final PrepareNextTurnHook prepareNextTurn;
    private final ToolExecution toolExecution;

    private AgentLoopConfig(Builder builder) {
        this.beforeToolCall = builder.beforeToolCall;
        this.afterToolCall = builder.afterToolCall;
        this.getSteeringMessages = builder.getSteeringMessages;
        this.getFollowUpMessages = builder.getFollowUpMessages;
        this.shouldStopAfterTurn = builder.shouldStopAfterTurn;
        this.prepareNextTurn = builder.prepareNextTurn;
        this.toolExecution = builder.toolExecution;
    }

    public static Builder builder() {
        return new Builder();
    }

    public BeforeToolCallHook beforeToolCall() {
        return beforeToolCall;
    }

    public AfterToolCallHook afterToolCall() {
        return afterToolCall;
    }

    public SteeringHook getSteeringMessages() {
        return getSteeringMessages;
    }

    public FollowUpHook getFollowUpMessages() {
        return getFollowUpMessages;
    }

    public ShouldStopAfterTurnHook shouldStopAfterTurn() {
        return shouldStopAfterTurn;
    }

    public PrepareNextTurnHook prepareNextTurn() {
        return prepareNextTurn;
    }

    public ToolExecution toolExecution() {
        return toolExecution;
    }

    public static final class Builder {
        private BeforeToolCallHook beforeToolCall = (tool, call) -> BeforeToolCallResult.allow();
        private AfterToolCallHook afterToolCall = (tool, call, result) -> result;
        private SteeringHook getSteeringMessages = () -> List.of();
        private FollowUpHook getFollowUpMessages = () -> List.of();
        private ShouldStopAfterTurnHook shouldStopAfterTurn = (assistant, toolResults, turnIndex) -> false;
        private PrepareNextTurnHook prepareNextTurn = (model, context, lastTurn) -> new TurnPlan(model, context);
        private ToolExecution toolExecution = ToolExecution.SEQUENTIAL;

        /** Permission gate: runs before every tool execution; a block skips {@code execute}. */
        public Builder beforeToolCall(BeforeToolCallHook hook) {
            this.beforeToolCall = hook;
            return this;
        }

        /** Finalizer: may field-wise override the tool result before it is emitted. */
        public Builder afterToolCall(AfterToolCallHook hook) {
            this.afterToolCall = hook;
            return this;
        }

        /** Steering queue: polled after every turn; messages are injected before the next LLM call. */
        public Builder getSteeringMessages(SteeringHook hook) {
            this.getSteeringMessages = hook;
            return this;
        }

        /** Follow-up queue: polled when the loop would stop; non-empty keeps it alive. */
        public Builder getFollowUpMessages(FollowUpHook hook) {
            this.getFollowUpMessages = hook;
            return this;
        }

        /** Runs after every turn; returning true ends the run. */
        public Builder shouldStopAfterTurn(ShouldStopAfterTurnHook hook) {
            this.shouldStopAfterTurn = hook;
            return this;
        }

        /** Applied before every turn after the first; may swap model and/or context. */
        public Builder prepareNextTurn(PrepareNextTurnHook hook) {
            this.prepareNextTurn = hook;
            return this;
        }

        /** How a batch of tool calls is executed (default {@code SEQUENTIAL}). */
        public Builder toolExecution(ToolExecution mode) {
            this.toolExecution = mode;
            return this;
        }

        public AgentLoopConfig build() {
            return new AgentLoopConfig(this);
        }
    }
}
