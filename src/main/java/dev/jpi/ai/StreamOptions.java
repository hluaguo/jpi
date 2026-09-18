package dev.jpi.ai;

/** Per-call options for a provider stream. */
public record StreamOptions(String apiKey, ThinkingLevel thinkingLevel) {

    public StreamOptions(String apiKey) {
        this(apiKey, ThinkingLevel.OFF);
    }

    public static StreamOptions none() {
        return new StreamOptions(null, ThinkingLevel.OFF);
    }
}
