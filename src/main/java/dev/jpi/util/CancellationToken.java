package dev.jpi.util;

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
        // the check must sit inside the monitor: pi's single-threaded Set.delete is
        // atomic, here two callers racing past an outer check would each run the list
        synchronized (this) {
            if (aborted) {
                return;
            }
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
