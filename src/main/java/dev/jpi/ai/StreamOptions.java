package dev.jpi.ai;

/** Per-call options for a provider stream. */
public record StreamOptions(String apiKey) {

    public static StreamOptions none() {
        return new StreamOptions(null);
    }
}
