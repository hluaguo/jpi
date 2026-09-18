package dev.jpi.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.jpi.Fixtures;
import dev.jpi.util.CancellationToken;

/**
 * The resilience contract: a flaky provider is retried on transient failures only,
 * failed attempts never reach the consumer, and cancellation is honored while
 * waiting — all against a fake, network-free provider layer.
 */
class RetryingStreamFnTest {

    private static final Model MODEL = Fixtures.model();

    // --- the flaky fake HTTP layer ---------------------------------------------

    /**
     * Fails the first {@code failTimes} calls with a classified error (after emitting
     * partial garbage a consumer must never see), then scripts one clean success.
     */
    private static final class FlakyProvider implements StreamFn {
        private final int failTimes;
        private final ErrorKind kind;
        private final List<AssistantMessageEvent> success;
        private int calls;

        FlakyProvider(int failTimes, ErrorKind kind, List<AssistantMessageEvent> success) {
            this.failTimes = failTimes;
            this.kind = kind;
            this.success = success;
        }

        int calls() {
            return calls;
        }

        @Override
        public AssistantMessageEventStream stream(Model model, Context context, StreamOptions options) {
            calls++;
            AssistantMessageEventStream out = new AssistantMessageEventStream();
            if (calls <= failTimes) {
                AssistantMessage pending = AssistantMessage.pending(model);
                out.push(new AssistantMessageEvent.Start(pending));
                out.push(new AssistantMessageEvent.TextDelta("garbage from failed attempt", pending));
                out.push(new AssistantMessageEvent.Error(pending
                        .withStopReason(StopReason.ERROR)
                        .withErrorMessage("HTTP 429: slow down")
                        .withDiagnostics(kind)));
            } else {
                success.forEach(out::push);
            }
            return out;
        }
    }

    /** One clean text response, event by event. */
    private static List<AssistantMessageEvent> successScript(Model model) {
        AssistantMessage empty = AssistantMessage.pending(model).withContent(List.of(new Content.Text("")));
        AssistantMessage partial = empty.withContent(List.of(new Content.Text("hello")));
        AssistantMessage finalMessage = partial.withStopReason(StopReason.STOP);
        return List.of(
                new AssistantMessageEvent.Start(AssistantMessage.pending(model)),
                new AssistantMessageEvent.TextStart(empty),
                new AssistantMessageEvent.TextDelta("hello", partial),
                new AssistantMessageEvent.TextEnd(partial),
                new AssistantMessageEvent.Done(finalMessage));
    }

    private static FlakyProvider flaky(int failTimes, ErrorKind kind) {
        return new FlakyProvider(failTimes, kind, successScript(MODEL));
    }

    private static List<AssistantMessageEvent> collect(AssistantMessageEventStream stream) {
        List<AssistantMessageEvent> events = new ArrayList<>();
        for (AssistantMessageEvent event : stream) {
            events.add(event);
        }
        return events;
    }

    // --- retry behavior ----------------------------------------------------------

    @Test
    void retriesUntilSuccessAndHidesFailedAttempts() {
        List<AssistantMessageEvent> script = successScript(MODEL);
        FlakyProvider provider = new FlakyProvider(2, ErrorKind.RATE_LIMIT, script);
        RetryingStreamFn retrying = new RetryingStreamFn(provider, new RetryPolicy(3, 1, 50));

        List<AssistantMessageEvent> events = collect(retrying.stream(MODEL, null, StreamOptions.none()));

        // exactly the success attempt, verbatim — failed attempts' partials never leak
        assertEquals(script, events);
        assertEquals(3, provider.calls());
        assertEquals(StopReason.STOP, events.get(events.size() - 1).partial().stopReason());
    }

    @Test
    void authFailuresAreNotRetried() {
        FlakyProvider provider = flaky(5, ErrorKind.AUTH);
        RetryingStreamFn retrying = new RetryingStreamFn(provider, new RetryPolicy(3, 1, 50));

        List<AssistantMessageEvent> events = collect(retrying.stream(MODEL, null, StreamOptions.none()));

        assertEquals(1, provider.calls());
        // the surfaced attempt is verbatim, partials included
        AssistantMessage last = events.get(events.size() - 1).partial();
        assertEquals(StopReason.ERROR, last.stopReason());
        assertEquals(ErrorKind.AUTH, last.diagnostics());
    }

    @Test
    void contextOverflowIsNotRetried() {
        FlakyProvider provider = flaky(5, ErrorKind.CONTEXT_OVERFLOW);
        RetryingStreamFn retrying = new RetryingStreamFn(provider, new RetryPolicy(3, 1, 50));

        collect(retrying.stream(MODEL, null, StreamOptions.none()));

        assertEquals(1, provider.calls());
    }

    @Test
    void abortedStreamsAreNotRetried() {
        StreamFn aborting = (model, context, options) -> {
            AssistantMessageEventStream out = new AssistantMessageEventStream();
            out.push(new AssistantMessageEvent.Error(
                    AssistantMessage.pending(model).withStopReason(StopReason.ABORTED)));
            return out;
        };
        RetryingStreamFn retrying = new RetryingStreamFn(aborting, new RetryPolicy(3, 1, 50));

        List<AssistantMessageEvent> events = collect(retrying.stream(MODEL, null, StreamOptions.none()));

        assertEquals(1, events.size());
        assertEquals(StopReason.ABORTED, events.get(0).partial().stopReason());
    }

    @Test
    void givesUpAfterMaxRetriesSurfacingLastError() {
        FlakyProvider provider = flaky(Integer.MAX_VALUE, ErrorKind.SERVER);
        RetryingStreamFn retrying = new RetryingStreamFn(provider, new RetryPolicy(2, 1, 50));

        List<AssistantMessageEvent> events = collect(retrying.stream(MODEL, null, StreamOptions.none()));

        assertEquals(3, provider.calls());
        AssistantMessage last = events.get(events.size() - 1).partial();
        assertEquals(StopReason.ERROR, last.stopReason());
        assertEquals(ErrorKind.SERVER, last.diagnostics());
    }

    /** "Fail fast past the cap": a backoff beyond maxRetryDelayMs ends the retry loop immediately. */
    @Test
    void failsFastWhenBackoffExceedsCap() {
        FlakyProvider provider = flaky(Integer.MAX_VALUE, ErrorKind.RATE_LIMIT);
        RetryingStreamFn retrying = new RetryingStreamFn(provider, new RetryPolicy(5, 1_000_000, 1_000));

        List<AssistantMessageEvent> events = collect(retrying.stream(MODEL, null, StreamOptions.none()));

        assertEquals(1, provider.calls());
        assertEquals(StopReason.ERROR, events.get(events.size() - 1).partial().stopReason());
    }

    // --- cancellation ---------------------------------------------------------

    @Test
    void abortWhileWaitingInBackoffSurfacesAborted() throws Exception {
        CancellationToken token = new CancellationToken();
        FlakyProvider provider = flaky(Integer.MAX_VALUE, ErrorKind.SERVER);
        RetryingStreamFn retrying = new RetryingStreamFn(provider, new RetryPolicy(5, 30_000, 60_000));

        Thread runner = new Thread(() -> collect(retrying.stream(MODEL, null,
                new StreamOptions(null, ThinkingLevel.OFF, token))));
        runner.start();
        while (provider.calls() == 0) {
            Thread.sleep(5);
        }
        token.abort();
        runner.join(5_000);

        assertEquals(1, provider.calls());
        assertTrue(!runner.isAlive());
    }

    @Test
    void alreadyAbortedTokenNeverCallsTheProvider() {
        CancellationToken token = new CancellationToken();
        token.abort();
        FlakyProvider provider = flaky(Integer.MAX_VALUE, ErrorKind.RATE_LIMIT);
        RetryingStreamFn retrying = new RetryingStreamFn(provider, new RetryPolicy(3, 1, 50));

        List<AssistantMessageEvent> events = collect(retrying.stream(MODEL, null,
                new StreamOptions(null, ThinkingLevel.OFF, token)));

        assertEquals(0, provider.calls());
        assertEquals(1, events.size());
        assertEquals(StopReason.ABORTED, events.get(0).partial().stopReason());
    }

    // --- pass-through -----------------------------------------------------------

    @Test
    void successfulStreamPassesThroughUntouched() {
        FlakyProvider provider = flaky(0, ErrorKind.RATE_LIMIT);
        RetryingStreamFn retrying = new RetryingStreamFn(provider, new RetryPolicy(3, 1, 50));

        List<AssistantMessageEvent> events = collect(retrying.stream(MODEL, null, StreamOptions.none()));

        assertEquals(successScript(MODEL), events);
        assertEquals(1, provider.calls());
    }
}
