package dev.jpi.agent;

import java.util.ArrayList;
import java.util.List;

/**
 * A cooperative cancellation flag: the loop and tools poll {@link #isAborted}; abort
 * sources call {@link #abort()}, which is idempotent and fires registered listeners.
 */
public final class CancellationToken {

    private volatile boolean aborted;
    private final List<Runnable> listeners = new ArrayList<>();

    public boolean isAborted() {
        return aborted;
    }

    /** Marks the token aborted; idempotent. Runs listener callbacks in registration order. */
    public void abort() {
        if (aborted) {
            return;
        }
        synchronized (this) {
            aborted = true;
            List.copyOf(listeners).forEach(Runnable::run);
        }
    }

    /** Registers a callback run immediately if already aborted, otherwise on abort. */
    public synchronized void onAbort(Runnable listener) {
        if (aborted) {
            listener.run();
        } else {
            listeners.add(listener);
        }
    }
}
