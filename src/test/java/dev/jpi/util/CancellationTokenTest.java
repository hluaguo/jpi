package dev.jpi.util;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Seam: {@link CancellationToken} idempotence — abort sources (loop, tools, retry)
 * rely on listeners firing exactly once no matter how many threads call abort.
 */
class CancellationTokenTest {

    @Test
    void abortRunsListenersExactlyOnceUnderRacingCallers() throws Exception {
        // the buggy check sat outside the synchronized block: callers that pass it
        // simultaneously each enter the lock, set aborted and re-run the listener list
        for (int attempt = 0; attempt < 200; attempt++) {
            CancellationToken token = new CancellationToken();
            AtomicInteger fired = new AtomicInteger();
            token.onAbort(fired::incrementAndGet);
            int callers = 8;
            CyclicBarrier barrier = new CyclicBarrier(callers);
            List<Thread> threads = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                threads.add(new Thread(() -> {
                    try {
                        barrier.await(5, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        return;
                    }
                    token.abort();
                }));
            }
            for (Thread t : threads) {
                t.start();
            }
            for (Thread t : threads) {
                t.join(5000);
            }
            assertEquals(1, fired.get(), "listeners fired " + fired.get() + " times on attempt " + attempt);
            assertTrue(token.isAborted());
        }
    }

    @Test
    void onAbortAfterAbortRunsImmediately() {
        CancellationToken token = new CancellationToken();
        AtomicInteger fired = new AtomicInteger();
        token.abort();
        token.onAbort(fired::incrementAndGet);
        token.abort(); // second abort is a no-op
        assertEquals(1, fired.get());
    }
}
