package dev.jpi.ai;

/**
 * The machine-readable class of a failed provider call, carried in the error
 * {@link AssistantMessage}'s {@code diagnostics} field.
 *
 * <p><em>Why an enum instead of letting consumers parse {@code errorMessage}:</em>
 * retry policy, UI badges, and context guards all need to react to <em>kind</em>
 * (retry a RATE_LIMIT, warn on CONTEXT_OVERFLOW, fail fast on AUTH) — that decision
 * must not depend on provider-specific message strings. Classification happens once,
 * at the adapter boundary where the status code or exception is still in hand.
 */
public enum ErrorKind {
    AUTH,
    RATE_LIMIT,
    SERVER,
    NETWORK,
    CONTEXT_OVERFLOW,
    UNKNOWN
}
