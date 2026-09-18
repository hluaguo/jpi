package dev.jpi.ai;

/**
 * Why the assistant message ended.
 *
 * <p><em>Why PENDING and the failure values exist:</em> one enum covers the whole
 * lifecycle — partial ({@code PENDING}), success ({@code STOP}/{@code TOOL_USE}/...),
 * and failure ({@code ERROR}/{@code ABORTED}) — so a failure needs no separate type,
 * no exception channel, and no special transcript handling. It is data the consumer
 * inspects, exactly like any other stop reason.
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
