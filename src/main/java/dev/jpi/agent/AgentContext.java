package dev.jpi.agent;

import dev.jpi.ai.Message;

import java.util.List;
import java.util.stream.Collectors;

/**
 * The loop's working snapshot: system prompt, full message transcript, and available tools.
 */
public record AgentContext(String systemPrompt, List<Message> messages, List<AgentTool> tools) {

    public AgentContext {
        messages = messages == null ? List.of() : List.copyOf(messages);
        tools = tools == null ? List.of() : List.copyOf(tools);
    }

    /** The wire-level tool declarations for this context. */
    public List<dev.jpi.ai.Tool> toolDefinitions() {
        return tools.stream()
                .map(t -> new dev.jpi.ai.Tool(t.name(), t.description(), t.parameters()))
                .collect(Collectors.toUnmodifiableList());
    }
}
