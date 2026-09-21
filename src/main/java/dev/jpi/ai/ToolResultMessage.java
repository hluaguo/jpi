package dev.jpi.ai;

import java.util.Collections;
import java.util.LinkedHashMap;
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
        // a defensive copy, not a view: a later caller mutation must not reshape a
        // recorded message (pi's wire objects are fresh per parse — same semantics).
        // LinkedHashMap over Map.copyOf: argument/details maps may carry null values
        // (JSON null is legal), which Map.copyOf rejects
        details = details == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }
}
