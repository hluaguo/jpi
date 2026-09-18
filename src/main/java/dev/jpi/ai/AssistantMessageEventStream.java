package dev.jpi.ai;

/**
 * An {@link EventStream} of assistant events finishing with the final assistant
 * message as its result.
 */
public class AssistantMessageEventStream extends EventStream<AssistantMessageEvent, AssistantMessage> {

    public AssistantMessageEventStream() {
        super(AssistantMessageEvent::isTerminal, AssistantMessageEvent::partial);
    }
}
