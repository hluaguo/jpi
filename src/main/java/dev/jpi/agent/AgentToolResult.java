package dev.jpi.agent;

import java.util.List;
import java.util.Map;

import dev.jpi.ai.Content;
import dev.jpi.ai.Usage;

/**
 * The outcome of one tool execution. Failures are not represented here: a tool that
 * fails throws, and the loop synthesizes the error tool result.
 *
 * @param terminate when set on <em>every</em> result of a batch, the loop stops after
 *                  appending the tool results instead of calling the LLM again
 */
public record AgentToolResult(List<Content> content, Map<String, Object> details, Usage usage, boolean terminate) {

    public AgentToolResult {
        content = content == null ? List.of() : List.copyOf(content);
        details = details == null ? Map.of() : Map.copyOf(details);
    }

    public static AgentToolResult text(String text) {
        return new AgentToolResult(List.of(new Content.Text(text)), Map.of(), null, false);
    }
}
