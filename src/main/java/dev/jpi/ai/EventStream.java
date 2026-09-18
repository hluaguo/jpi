package dev.jpi.ai;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * A single-consumer push/pull async event queue that terminates on a "complete" event
 * or an explicit {@link #end}, and exposes the final result as a future.
 *
 * <p>This mirrors pi's {@code EventStream}: a producer pushes events of type {@code T}
 * ({@link #push}); a consumer iterates and receives them in order. Iteration stops at
 * the first event satisfying the {@code isComplete} predicate — the <em>terminal
 * event</em> — or when the producer calls {@link #end}. The result of type {@code R}
 * is extracted from the terminal event via {@code extractResult}, or supplied directly
 * by {@link #end}, and is available from {@link #result()}.
 *
 * <p>Failures are data, not exceptions: the caller inspects the result, the stream
 * never throws across the boundary. Misuse (pushing after the stream finished) is a
 * local programming error and throws {@link IllegalStateException}.
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
     * Appends an event. If the event is terminal (satisfies {@code isComplete}), the
     * stream is finished and its result is extracted from this event.
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
     * Finishes the stream without a terminal event: consumers still receive all
     * previously pushed events, then iteration ends with the given result.
     *
     * @throws IllegalStateException if the stream has already finished
     */
    public synchronized void end(R result) {
        if (finished) {
            throw new IllegalStateException("stream already finished");
        }
        queue.add(END);
        finish(result);
    }

    private synchronized void finish(R result) {
        if (!finished) {
            finished = true;
            queue.add(END);
            this.result.complete(result);
        }
    }

    /**
     * Returns the future holding the stream's result; completes when the stream
     * finishes (terminal event or {@link #end}).
     */
    public CompletableFuture<R> result() {
        return result;
    }

    /** Single-shot iterator; blocks while waiting for the next event. */
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
