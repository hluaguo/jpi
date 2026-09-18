package dev.jpi.ai;

import dev.jpi.util.CancellationToken;

/** Per-call options for a provider stream; {@code cancel} lets decorators (retry)
 * and providers observe the caller's cancellation. */
public record StreamOptions(String apiKey, ThinkingLevel thinkingLevel, CancellationToken cancel) {

    public StreamOptions(String apiKey, ThinkingLevel thinkingLevel) {
        this(apiKey, thinkingLevel, null);
    }

    public StreamOptions(String apiKey) {
        this(apiKey, ThinkingLevel.OFF, null);
    }

    public static StreamOptions none() {
        return new StreamOptions(null, ThinkingLevel.OFF, null);
    }
}
