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

    /** A turn began; {@code turnIndex} counts from 0. */
    record TurnStart(int turnIndex) implements AgentEvent {
    }

    /** A message was added to the transcript: an injected prompt/steering message, or the assistant partial. */
    record MessageStart(Message message) implements AgentEvent {
    }

    /** The assistant partial advanced; carries the raw {@link AssistantMessageEvent}. */
    record MessageUpdate(AssistantMessageEvent update) implements AgentEvent {
    }

    /** A message reached its final form. */
    record MessageEnd(Message message) implements AgentEvent {
    }

    /** A tool call started executing. */
    record ToolExecutionStart(String toolCallId, String toolName, Map<String, Object> args) implements AgentEvent {
    }

    /** A tool streamed partial output. */
    record ToolExecutionUpdate(String toolCallId, Map<String, Object> partial) implements AgentEvent {
    }

    /** A tool call finished; carries the finalized tool result message. */
    record ToolExecutionEnd(String toolCallId, String toolName, ToolResultMessage result) implements AgentEvent {
    }

    /** A turn finished: the final assistant message and its tool results (if any). */
    record TurnEnd(AssistantMessage assistant, List<ToolResultMessage> toolResults) implements AgentEvent {
    }

    /** The run ended; carries all messages added during the run. */
    record End(List<Message> messages) implements AgentEvent {
    }
}
