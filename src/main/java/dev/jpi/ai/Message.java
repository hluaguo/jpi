package dev.jpi.ai;

/** A message in a conversation. */
public sealed interface Message permits UserMessage, AssistantMessage, ToolResultMessage {
}
