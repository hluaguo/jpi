package dev.jpi.ai;

/**
 * A model that can be streamed from, as identified on the wire.
 *
 * @param cost published per-million rates, null when the provider publishes none
 */
public record Model(
        String id,
        String api,
        String provider,
        String baseUrl,
        int contextWindow,
        int maxTokens,
        Cost cost) {

    public Model(String id, String api, String provider, String baseUrl, int contextWindow, int maxTokens) {
        this(id, api, provider, baseUrl, contextWindow, maxTokens, null);
    }

    /** Published per-million USD rates; the pairing of {@link Usage} token counts with money. */
    public record Cost(double input, double output, double cacheRead, double cacheWrite) {
    }
}
