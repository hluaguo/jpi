package dev.jpi.ai;

/**
 * Streams one assistant response for the given model and context.
 *
 * <p>Implementations must not throw: failures are encoded in the returned stream as a
 * terminal {@link AssistantMessageEvent.Error} event (a message with
 * {@code stopReason == ERROR} or {@code ABORTED}).
 */
@FunctionalInterface
public interface StreamFn {

    AssistantMessageEventStream stream(Model model, Context context, StreamOptions options);
}
