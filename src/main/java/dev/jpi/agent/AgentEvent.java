package dev.jpi.agent;

import java.util.List;
import java.util.Map;

import dev.jpi.ai.AssistantMessage;
import dev.jpi.ai.AssistantMessageEvent;
import dev.jpi.ai.Message;
import dev.jpi.ai.ToolResultMessage;

/**
 * The loop's lifecycle event protocol — the entire contract downstream consumers
 * (UIs, persistence) need. Listeners are invoked synchronously in subscription order.
 */
public sealed interface AgentEvent
        permits AgentEvent.Start, AgentEvent.TurnStart, AgentEvent.MessageStart,
        AgentEvent.MessageUpdate, AgentEvent.MessageEnd, AgentEvent.ToolExecutionStart,
        AgentEvent.ToolExecutionUpdate, AgentEvent.ToolExecutionEnd,
        AgentEvent.TurnEnd, AgentEvent.End {

    /** The run began. */
    record Start() implements AgentEvent {
    }

    record TurnStart(int turnIndex) implements AgentEvent {
    }

    /** An injected prompt/steering message, or the assistant partial. */
    record MessageStart(Message message) implements AgentEvent {
    }

    /** Carries the raw {@link AssistantMessageEvent}. */
    record MessageUpdate(AssistantMessageEvent update) implements AgentEvent {
    }

    record MessageEnd(Message message) implements AgentEvent {
    }

    record ToolExecutionStart(String toolCallId, String toolName, Map<String, Object> args) implements AgentEvent {
    }

    record ToolExecutionUpdate(String toolCallId, Map<String, Object> partial) implements AgentEvent {
    }

    record ToolExecutionEnd(String toolCallId, String toolName, ToolResultMessage result) implements AgentEvent {
    }

    /** Final assistant message plus its tool results, in transcript order. */
    record TurnEnd(AssistantMessage assistant, List<ToolResultMessage> toolResults) implements AgentEvent {
    }

    /** Carries all messages added during the run. */
    record End(List<Message> messages) implements AgentEvent {
    }
}
