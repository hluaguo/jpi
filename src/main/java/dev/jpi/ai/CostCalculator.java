package dev.jpi.ai;

/**
 * Fills a {@link Usage}'s cost breakdown from a model's published per-million rates.
 *
 * <p><em>Why this is the only place money is computed:</em> token counts are
 * provider-reported (authoritative); the rates are model metadata. Cost is pure
 * arithmetic joining the two — no estimation, no heuristics anywhere in jpi.
 */
public final class CostCalculator {

    public static Usage costOf(Usage usage, Model model) {
        if (model == null || model.cost() == null) {
            return usage;
        }
        Model.Cost rate = model.cost();
        return new Usage(
                usage.input(), usage.output(), usage.cacheRead(), usage.cacheWrite(),
                usage.input() / 1_000_000.0 * rate.input(),
                usage.output() / 1_000_000.0 * rate.output(),
                usage.cacheRead() / 1_000_000.0 * rate.cacheRead(),
                usage.cacheWrite() / 1_000_000.0 * rate.cacheWrite());
    }

    private CostCalculator() {
    }
}
