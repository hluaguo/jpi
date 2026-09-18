package dev.jpi.ai;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * A single-consumer push/pull event queue that terminates on a "complete" event
 * or an explicit {@link #end}, and exposes the final result as a future.
 *
 * <p><em>Why:</em> providers deliver callbacks on network threads while consumers
 * want a plain blocking for-each and one final value. Funneling every provider
 * through this queue decouples those two worlds: the producer can push as fast or
 * as slow as the wire delivers, the consumer reads with ordinary iteration, and
 * the result future gives callers that only care about the outcome a cheap handle.
 * The completion predicate is injected so each provider's own "done" convention
 * collapses into one shared mechanism instead of one wrapper class per provider.
 *
 * <p>Failures are data, not exceptions: a failed stream is still a well-formed
 * stream whose result says so, because the loop must never catch provider
 * exceptions to stay a total function. Misuse (pushing after finish) is a local
 * programming error and throws {@link IllegalStateException}.
 *
 * @param <T> event type
 * @param <R> result type
 */
public class EventStream<T, R> implements Iterable<T> {

    private static final Object END = new Object();

    private final BlockingQueue<Object> queue = new LinkedBlockingQueue<>();
    private final Predicate<T> isComplete;
    private final Function<T, R> extractResult;
    private final CompletableFuture<R> result = new CompletableFuture<>();
    private boolean finished;

    /**
     * Creates a stream driven by the given completion predicate and result extractor.
     */
    public EventStream(Predicate<T> isComplete, Function<T, R> extractResult) {
        this.isComplete = isComplete;
        this.extractResult = extractResult;
    }

    /**
     * Appends an event; if it satisfies {@code isComplete} the stream finishes and the
     * result is extracted from it.
     *
     * @throws IllegalStateException if the stream has already finished
     */
    public synchronized void push(T event) {
        if (finished) {
            throw new IllegalStateException("stream already finished");
        }
        queue.add(event);
        if (isComplete.test(event)) {
            finish(extractResult.apply(event));
        }
    }

    /**
     * Finishes the stream without a terminal event; consumers still receive every
     * previously pushed event.
     */
    public synchronized void end(R result) {
        if (finished) {
            throw new IllegalStateException("stream already finished");
        }
        finish(result);
    }

    private synchronized void finish(R result) {
        if (!finished) {
            finished = true;
            queue.add(END);
            this.result.complete(result);
        }
    }

    public CompletableFuture<R> result() {
        return result;
    }

    /** Single-shot; blocks until the next event arrives. */
    @Override
    public Iterator<T> iterator() {
        return new Iterator<>() {
            private T next;
            private boolean done;

            @Override
            public boolean hasNext() {
                if (done || next != null) {
                    return !done;
                }
                try {
                    Object item = queue.take();
                    if (item == END) {
                        done = true;
                        return false;
                    }
                    @SuppressWarnings("unchecked")
                    T event = (T) item;
                    next = event;
                    return true;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    done = true;
                    return false;
                }
            }

            @Override
            public T next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                T event = next;
                next = null;
                return event;
            }
        };
    }
}
