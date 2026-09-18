package dev.jpi.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Backoff arithmetic and construction validation. */
class RetryPolicyTest {

    @Test
    void backoffDoublesPerRetry() {
        RetryPolicy policy = new RetryPolicy(5, 500, 60_000);
        assertEquals(500, policy.backoffMs(1));
        assertEquals(1000, policy.backoffMs(2));
        assertEquals(2000, policy.backoffMs(3));
        assertEquals(4000, policy.backoffMs(4));
        assertEquals(8000, policy.backoffMs(5));
    }

    @Test
    void rejectsNegativeValues() {
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(-1, 1, 10));
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(1, -1, 10));
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(1, 1, -10));
    }

    @Test
    void saturatesInsteadOfOverflowing() {
        RetryPolicy policy = new RetryPolicy(80, 1_000_000_000, Long.MAX_VALUE);
        assertTrue(policy.backoffMs(80) > 0);
    }
}
