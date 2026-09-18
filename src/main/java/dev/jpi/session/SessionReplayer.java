package dev.jpi.session;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import dev.jpi.agent.AgentEvent;
import dev.jpi.ai.AssistantMessage;
import dev.jpi.ai.AssistantMessageEvent;
import dev.jpi.ai.Message;
import dev.jpi.ai.StopReason;
import dev.jpi.ai.providers.ScriptedProvider;

/**
 * Turns a recorded session back into live artifacts: the transcript, the verbatim
 * event stream, and — the demo-day fallback — a {@link ScriptedProvider} that
 * replays the recorded assistant responses as if the LLM had answered again.
 */
public final class SessionReplayer {

    /** Feeds recorded events verbatim to a consumer (a UI or state builder). */
    public static void replay(List<SessionRecord> records, Consumer<AgentEvent> consumer) {
        records.forEach(r -> consumer.accept(r.event()));
    }

    /** The run's messages in order; falls back to message-end events if the End line was torn away. */
    public static List<Message> transcript(List<SessionRecord> records) {
        for (SessionRecord record : records) {
            if (record.event() instanceof AgentEvent.End(List<Message> messages)) {
                return messages;
            }
        }
        List<Message> messages = new ArrayList<>();
        for (SessionRecord record : records) {
            if (record.event() instanceof AgentEvent.MessageEnd(Message message)) {
                messages.add(message);
            }
        }
        return List.copyOf(messages);
    }

    /**
     * A provider scripting one call per recorded assistant response. The loop emits
     * exactly one MessageStart / update / MessageEnd group per LLM call; the raw
     * streaming events inside those groups are what get replayed.
     */
    public static ScriptedProvider toProvider(List<SessionRecord> records) {
        ScriptedProvider.Builder builder = ScriptedProvider.builder();
        List<AssistantMessageEvent> current = null;
        for (SessionRecord record : records) {
            if (record.event() instanceof AgentEvent.MessageStart(Message message)
                    && message instanceof AssistantMessage assistant && current == null) {
                current = new ArrayList<>();
                current.add(new AssistantMessageEvent.Start(assistant));
            } else if (record.event() instanceof AgentEvent.MessageUpdate(AssistantMessageEvent update)
                    && current != null) {
                current.add(update);
            } else if (record.event() instanceof AgentEvent.MessageEnd(Message message)
                    && message instanceof AssistantMessage assistant && current != null) {
                current.add(terminal(assistant));
                builder.events(List.copyOf(current));
                current = null;
            }
        }
        return builder.build();
    }

    private static AssistantMessageEvent terminal(AssistantMessage finalMessage) {
        return finalMessage.stopReason() == StopReason.ERROR || finalMessage.stopReason() == StopReason.ABORTED
                ? new AssistantMessageEvent.Error(finalMessage)
                : new AssistantMessageEvent.Done(finalMessage);
    }

    private SessionReplayer() {
    }
}
