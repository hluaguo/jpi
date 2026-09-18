package dev.jpi.ai;

/**
 * An {@link EventStream} of assistant events finishing with the final assistant
 * message as its result.
 *
 * <p><em>Why a type alias:</em> the {@code isComplete}/{@code extractResult}
 * wiring — "a done/error event ends the stream and the outcome is its message" —
 * encodes the streaming protocol once, so {@link dev.jpi.ai.StreamFn} implementations
 * can hand back this type and be understood by the loop without extra ceremony.
 */
public class AssistantMessageEventStream extends EventStream<AssistantMessageEvent, AssistantMessage> {

    public AssistantMessageEventStream() {
        super(AssistantMessageEvent::isTerminal, AssistantMessageEvent::partial);
    }
}
