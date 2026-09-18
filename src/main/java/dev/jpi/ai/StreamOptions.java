package dev.jpi.ai;

import dev.jpi.util.CancellationToken;

/**
 * Per-call options for a provider stream; {@code cancel} lets decorators (retry)
 * and providers observe the caller's cancellation. {@code promptCaching} asks
 * cache-capable providers to mark the request for prefix caching (Anthropic
 * ephemeral cache_control); it defaults to on because multi-turn runs otherwise
 * repay full input price every turn.
 */
public record StreamOptions(String apiKey, ThinkingLevel thinkingLevel, CancellationToken cancel,
                            boolean promptCaching) {

    public StreamOptions(String apiKey, ThinkingLevel thinkingLevel, CancellationToken cancel) {
        this(apiKey, thinkingLevel, cancel, true);
    }

    public StreamOptions(String apiKey, ThinkingLevel thinkingLevel) {
        this(apiKey, thinkingLevel, null, true);
    }

    public StreamOptions(String apiKey) {
        this(apiKey, ThinkingLevel.OFF, null, true);
    }

    public static StreamOptions none() {
        return new StreamOptions(null, ThinkingLevel.OFF, null, true);
    }
}
