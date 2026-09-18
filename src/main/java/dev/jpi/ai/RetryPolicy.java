package dev.jpi.ai;

/**
 * When to give up and how long to wait between attempts: {@code maxRetries} extra
 * attempts after the first, waiting {@code baseDelayMs * 2^(retry-1)} between them
 * (plus jitter at the caller). A computed backoff beyond {@code maxRetryDelayMs}
 * ends the retry loop instead of sleeping — a rate-limit that needs minutes of
 * backoff is not worth holding the caller hostage for.
 */
public record RetryPolicy(int maxRetries, long baseDelayMs, long maxRetryDelayMs) {

    public RetryPolicy {
        if (maxRetries < 0 || baseDelayMs < 0 || maxRetryDelayMs < 0) {
            throw new IllegalArgumentException("retry policy values must be non-negative");
        }
    }

    /** Backoff before the given retry (1-based); saturates instead of overflowing. */
    public long backoffMs(int retry) {
        long delay = baseDelayMs;
        for (int i = 1; i < retry && delay <= Long.MAX_VALUE / 2; i++) {
            delay *= 2;
        }
        return delay;
    }
}
