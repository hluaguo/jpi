package dev.jpi.ai;

/**
 * The unified streaming protocol every provider normalizes to.
 *
 * <p><em>Why:</em> one event vocabulary is what lets the agent loop (and any UI)
 * treat Anthropic, OpenAI-compatible endpoints, and test fakes identically — provider
 * quirks are absorbed in the adapters, never downstream. Every event carries the live
 * partial {@link AssistantMessage} so a UI can render "response so far" without
 * reassembling deltas itself; the terminal events ({@link Done}, {@link Error})
 * carry the final message, which is the single artifact the transcript keeps.
 */
public sealed interface AssistantMessageEvent
        permits AssistantMessageEvent.Start, AssistantMessageEvent.TextStart,
        AssistantMessageEvent.TextDelta, AssistantMessageEvent.TextEnd,
        AssistantMessageEvent.ThinkingStart, AssistantMessageEvent.ThinkingDelta,
        AssistantMessageEvent.ThinkingEnd, AssistantMessageEvent.ToolCallStart,
        AssistantMessageEvent.ToolCallDelta, AssistantMessageEvent.ToolCallEnd,
        AssistantMessageEvent.Done, AssistantMessageEvent.Error {

    /** The response so far; the final message on terminal events. */
    AssistantMessage partial();

    default boolean isTerminal() {
        return this instanceof Done || this instanceof Error;
    }

    /** Stream accepted; carries the empty partial. */
    record Start(AssistantMessage partial) implements AssistantMessageEvent {
    }

    record TextStart(AssistantMessage partial) implements AssistantMessageEvent {
    }

    /** Incremental text; the partial's last text block includes this {@code delta}. */
    record TextDelta(String delta, AssistantMessage partial) implements AssistantMessageEvent {
    }

    record TextEnd(AssistantMessage partial) implements AssistantMessageEvent {
    }

    record ThinkingStart(AssistantMessage partial) implements AssistantMessageEvent {
    }

    record ThinkingDelta(String delta, AssistantMessage partial) implements AssistantMessageEvent {
    }

    record ThinkingEnd(AssistantMessage partial) implements AssistantMessageEvent {
    }

    /** A tool call block started; arguments arrive as {@link ToolCallDelta} chunks. */
    record ToolCallStart(String id, String name, AssistantMessage partial) implements AssistantMessageEvent {
    }

    /** A raw JSON chunk of the tool call's arguments. */
    record ToolCallDelta(String id, String delta, AssistantMessage partial) implements AssistantMessageEvent {
    }

    /** Tool call complete; the partial's tool call now carries parsed arguments. */
    record ToolCallEnd(String id, AssistantMessage partial) implements AssistantMessageEvent {
    }

    /** Terminal, successful; {@code partial().stopReason()} is {@code STOP}, {@code LENGTH}, {@code TOOL_USE} or {@code DEFERRED}. */
    record Done(AssistantMessage partial) implements AssistantMessageEvent {
    }

    /** Terminal, failed; {@code partial().stopReason()} is {@code ERROR} or {@code ABORTED}. */
    record Error(AssistantMessage partial) implements AssistantMessageEvent {
    }
}
