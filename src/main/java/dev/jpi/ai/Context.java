package dev.jpi.ai;

import java.util.List;

/**
 * The exact payload handed to a provider: optional system prompt, the message
 * transcript, and the available tools.
 */
public record Context(String systemPrompt, List<Message> messages, List<Tool> tools) {

    public Context {
        messages = messages == null ? List.of() : List.copyOf(messages);
        tools = tools == null ? List.of() : List.copyOf(tools);
    }
}
