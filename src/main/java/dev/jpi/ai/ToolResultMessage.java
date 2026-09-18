package dev.jpi.ai;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/** The result of a tool call, reported back to the model; content is text/image blocks. */
public record ToolResultMessage(
        String toolCallId,
        String toolName,
        List<Content> content,
        Map<String, Object> details,
        boolean isError,
        long timestamp) implements Message {

    public ToolResultMessage {
        content = content == null ? List.of() : List.copyOf(content);
        details = details == null ? Map.of() : Collections.unmodifiableMap(details);
    }
}
