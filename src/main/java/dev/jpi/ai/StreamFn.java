package dev.jpi.ai;

/**
 * Streams one assistant response for the given model and context.
 *
 * <p><em>Why implementions must not throw:</em> a thrown provider exception would
 * punch a hole through the loop, which then needs per-call try/catch to stay alive.
 * Encoding failure <em>in the stream</em> (a terminal {@link AssistantMessageEvent.Error}
 * whose message carries {@code stopReason == ERROR} or {@code ABORTED}) keeps the loop
 * a pure, total function — and makes failures loggable, replayable, and inspectable
 * like any other message.
 */
@FunctionalInterface
public interface StreamFn {

    AssistantMessageEventStream stream(Model model, Context context, StreamOptions options);
}
