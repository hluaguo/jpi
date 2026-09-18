package dev.jpi.agent;

/**
 * Per-run totals reduced from {@link AgentEvent}s — the numbers a UI's cost/status
 * badge needs. Token counts are provider-reported (never estimated); {@code cost}
 * joins those counts with the model's published rates via {@code CostCalculator};
 * {@code cacheHits} counts responses that actually read the prompt cache.
 */
public record RunStats(
        long inputTokens,
        long outputTokens,
        long cacheReadTokens,
        long cacheWriteTokens,
        int cacheHits,
        double cost,
        long durationMs,
        int messages,
        int toolResults) {

    public static final RunStats ZERO =
            new RunStats(0, 0, 0, 0, 0, 0.0, 0, 0, 0);
}
