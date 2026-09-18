package dev.jpi.ai;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Seam 1: {@link EventStream} — push/pull async queue with terminal-event semantics.
 */
class EventStreamTest {

    @Test
    void eventsComeOutInPushOrder() {
        EventStream<String, String> stream = new EventStream<>(e -> false, e -> e);
        stream.push("a");
        stream.push("b");
        stream.end("r");

        assertEquals(List.of("a", "b"), collect(stream));
        assertEquals("r", stream.result().join());
    }

    @Test
    void iterationStopsAtTerminalEventAndExtractsResult() {
        EventStream<String, String> stream =
                new EventStream<>(e -> e.equals("done"), e -> "result:" + e);
        stream.push("a");
        stream.push("done");

        assertThrows(IllegalStateException.class, () -> stream.push("b"));
        assertThrows(IllegalStateException.class, () -> stream.end("x"));
        assertEquals(List.of("a", "done"), collect(stream));
        assertEquals("result:done", stream.result().join());
    }

    @Test
    void producerOnSeparateThreadDeliversAllEvents() throws Exception {
        EventStream<Integer, Integer> stream = new EventStream<>(e -> e < 0, e -> e);
        Thread producer = new Thread(() -> {
            try {
                for (int i = 1; i <= 5; i++) {
                    Thread.sleep(10);
                    stream.push(i);
                }
                stream.push(-1);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        producer.start();

        assertEquals(List.of(1, 2, 3, 4, 5, -1), collect(stream));
        assertEquals(-1, stream.result().get(5, TimeUnit.SECONDS));
    }

    private static <T> List<T> collect(Iterable<T> iterable) {
        List<T> out = new ArrayList<>();
        iterable.forEach(out::add);
        return out;
    }
}
