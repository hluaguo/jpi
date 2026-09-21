package dev.jpi.ai;

/**
 * Why the assistant message ended.
 *
 * <p><em>Why PENDING and the failure values exist:</em> one enum covers the whole
 * lifecycle — partial ({@code PENDING}), success ({@code STOP}/{@code TOOL_USE}/...),
 * and failure ({@code ERROR}/{@code ABORTED}) — so a failure needs no separate type,
 * no exception channel, and no special transcript handling. It is data the consumer
 * inspects, exactly like any other stop reason.
 *
 * <p>{@code DEFERRED} is reserved: pi produces it from its deferred-tools provider
 * (tool search loads definitions lazily mid-conversation), which jpi excludes from
 * scope — the value is kept so transcripts recorded by fuller pi ports still parse.
 */
public enum StopReason {
    PENDING,
    STOP,
    LENGTH,
    TOOL_USE,
    ERROR,
    ABORTED,
    DEFERRED
}
