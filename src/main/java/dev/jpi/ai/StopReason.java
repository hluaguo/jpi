package dev.jpi.ai;

/**
 * Why the assistant message ended. {@link #PENDING} marks a partial message still
 * streaming; {@link #ERROR} and {@link #ABORTED} encode failures — failures are data,
 * not exceptions.
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
