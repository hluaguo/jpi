package dev.jpi.ai;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Content blocks.
 *
 * <p><em>Why one sealed union instead of per-role types:</em> providers differ on
 * which block may appear where (user messages: text/image; assistant: text/thinking/
 * toolCall; tool results: text/image) — a single closed union keeps the model honest
 * for every role while adapters stay responsible for rejecting combinations their
 * wire format forbids.
 */
public sealed interface Content permits Content.Text, Content.Image, Content.Thinking, Content.ToolCall {

    /** A block of plain text. */
    record Text(String text) implements Content {
        public Text {
            if (text == null) text = "";
        }
    }

    /** A base64-encoded image. */
    record Image(String data, String mimeType) implements Content {
    }

    /** A chain-of-thought block; {@code signature} may be null, {@code redacted} marks redacted thinking. */
    record Thinking(String thinking, String signature, boolean redacted) implements Content {
        public Thinking(String thinking) {
            this(thinking, null, false);
        }
    }

    /** A tool invocation requested by the model; {@code arguments} is the parsed JSON object. */
    record ToolCall(String id, String name, Map<String, Object> arguments) implements Content {
        public ToolCall {
            // defensive copy, not a view — same snapshot semantics as
            // ToolResultMessage.details (LinkedHashMap tolerates JSON null values)
            arguments = arguments == null ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
        }
    }
}
