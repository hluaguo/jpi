package dev.jpi.ai;

/** A model that can be streamed from, as identified on the wire. */
public record Model(
        String id,
        String api,
        String provider,
        String baseUrl,
        int contextWindow,
        int maxTokens) {
}
