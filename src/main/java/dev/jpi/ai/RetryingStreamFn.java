package dev.jpi.ai;

import dev.jpi.util.CancellationToken;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * A {@link StreamFn} decorator adding retries with exponential backoff to a flaky
 * provider layer (pi's {@code provider-retry.js}). Retryable failures are the
 * transient {@link ErrorKind}s; AUTH, context overflow and aborts are surfaced as-is.
 *
 * <p><em>Why attempts are buffered:</em> failures are data here — a failed call is a
 * terminal {@link AssistantMessageEvent.Error}, not a throw — so by the time the
 * failure is known, earlier events of that attempt already exist. Buffering each
 * attempt and forwarding only the final one is what keeps the "one stream per LLM
 * call" invariant honest: the consumer never sees a failed attempt's partials, and a
 * retried-then-successful call looks exactly like an untroubled one. The cost is that
 * deltas arrive at attempt completion rather than live.
 *
 * <p>Backoff waits poll the {@link CancellationToken} carried in
 * {@link StreamOptions}; an abort while waiting ends the stream ABORTED immediately.
 */
public final class RetryingStreamFn implements StreamFn {

    /** Conservative default: 3 retries, 500 ms base, never schedule past 20 s. */
    public static final RetryPolicy DEFAULT_POLICY = new RetryPolicy(3, 500, 20_000);

    private static final long ABORT_POLL_MS = 25;

    private final StreamFn delegate;
    private final RetryPolicy policy;
    private final Random random = new Random();

    public RetryingStreamFn(StreamFn delegate) {
        this(delegate, DEFAULT_POLICY);
    }

    public RetryingStreamFn(StreamFn delegate, RetryPolicy policy) {
        this.delegate = delegate;
        this.policy = policy;
    }

    @Override
    public AssistantMessageEventStream stream(Model model, Context context, StreamOptions options) {
        CancellationToken token = options.cancel();
        AssistantMessageEventStream out = new AssistantMessageEventStream();

        for (int attempt = 0; ; attempt++) {
            if (token != null && token.isAborted()) {
                return aborted(out, model);
            }
            List<AssistantMessageEvent> events = drain(delegate.stream(model, context, options));
            int retry = attempt + 1;
            if (retryableFailure(events.get(events.size() - 1))
                    && retry <= policy.maxRetries()
                    && policy.backoffMs(retry) <= policy.maxRetryDelayMs()
                    && waitFor(policy.backoffMs(retry) + jitterMs(policy.backoffMs(retry)), token)) {
                continue;
            }
            events.forEach(out::push);
            return out;
        }
    }

    private static List<AssistantMessageEvent> drain(AssistantMessageEventStream stream) {
        List<AssistantMessageEvent> events = new ArrayList<>();
        for (AssistantMessageEvent event : stream) {
            events.add(event);
        }
        return events;
    }

    private static boolean retryableFailure(AssistantMessageEvent terminal) {
        return terminal instanceof AssistantMessageEvent.Error error
                && error.partial().stopReason() == StopReason.ERROR
                && ErrorClassifier.isRetryable(error.partial().diagnostics());
    }

    /** Sleeps in slices, polling the token; false if aborted meanwhile. Null token = never aborted. */
    private static boolean waitFor(long totalMs, CancellationToken token) {
        long remaining = totalMs;
        while (remaining > 0) {
            if (token != null && token.isAborted()) {
                return false;
            }
            long slice = Math.min(remaining, ABORT_POLL_MS);
            try {
                Thread.sleep(slice);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            remaining -= slice;
        }
        return token == null || !token.isAborted();
    }

    private long jitterMs(long backoffMs) {
        return backoffMs > 0 ? random.nextLong(backoffMs / 4 + 1) : 0;
    }

    private static AssistantMessageEventStream aborted(AssistantMessageEventStream out, Model model) {
        out.push(new AssistantMessageEvent.Error(
                AssistantMessage.pending(model)
                        .withStopReason(StopReason.ABORTED)
                        .withErrorMessage("aborted before the provider call completed")));
        return out;
    }
}
