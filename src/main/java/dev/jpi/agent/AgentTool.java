package dev.jpi.agent;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import dev.jpi.util.CancellationToken;

/**
 * A tool the agent loop can execute. Declaration fields ({@link #name}, {@link #description},
 * {@link #parameters}) are sent to the model; {@link #execute} runs the call.
 *
 * <p>Tools signal failure by <em>throwing</em>; the loop converts throws into error
 * tool results — the loop itself never propagates tool exceptions.
 */
public interface AgentTool {

    String name();

    String description();

    /** Argument schema, JSON-Schema-shaped. */
    Map<String, Object> parameters();

    /**
     * Overrides the batch execution mode for calls of this tool: if any tool call in
     * a batch targets a SEQUENTIAL tool, the whole batch runs sequentially — the
     * tool's work must never overlap, and one straggler must not parallelize its
     * siblings. {@code null} inherits the loop config.
     */
    default AgentLoopConfig.ToolExecution executionMode() {
        return null;
    }

    /** Short display label; defaults to {@link #name}. */
    default String label() {
        return name();
    }

    /**
     * Executes the call. {@code signal} is checked between units of work;
     * {@code onUpdate} streams partial output as detail maps.
     */
    AgentToolResult execute(String toolCallId, Map<String, Object> args,
                            CancellationToken signal, Consumer<Map<String, Object>> onUpdate);
}
