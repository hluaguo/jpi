package dev.jpi.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * Cost arithmetic as a worked example: 1M input, 0.5M output, 200k cache reads and
 * 100k cache writes against rates of $3/$15/$0.30/$3.75 per million.
 */
class CostCalculatorTest {

    private static final Model MODEL = new Model("claude-sonnet-4", "anthropic-messages", "anthropic",
            "http://localhost", 200000, 8192,
            new Model.Cost(3.0, 15.0, 0.30, 3.75));

    private static final Usage USAGE =
            new Usage(1_000_000, 500_000, 200_000, 100_000, 0, 0, 0, 0);

    @Test
    void costIsUsageTimesPerMillionRate() {
        Usage costed = CostCalculator.costOf(USAGE, MODEL);

        assertEquals(3.0, costed.costInput());
        assertEquals(7.5, costed.costOutput());
        assertEquals(0.06, costed.costCacheRead());
        assertEquals(0.375, costed.costCacheWrite());
        assertEquals(10.935, costed.totalCost());
    }

    @Test
    void tokenCountsArePreservedUnchanged() {
        assertEquals(1_000_000, costOf().input());
        assertEquals(500_000, costOf().output());
        assertEquals(200_000, costOf().cacheRead());
        assertEquals(100_000, costOf().cacheWrite());
    }

    /** Models without published rates cost nothing rather than throwing. */
    @Test
    void modelWithoutCostYieldsZeroCost() {
        Model free = new Model("m", "anthropic-messages", "anthropic", "http://localhost", 1000, 100);
        assertNull(free.cost());

        Usage costed = CostCalculator.costOf(USAGE, free);
        assertEquals(0.0, costed.totalCost());
        assertEquals(1_000_000, costed.input());
    }

    private static Usage costOf() {
        return CostCalculator.costOf(USAGE, MODEL);
    }
}
