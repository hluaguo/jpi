package dev.jpi.ai;

import java.util.Collections;
import java.util.Map;

/**
 * Content blocks. A {@link dev.jpi.ai.UserMessage} carries text/image blocks, an
 * {@link AssistantMessage} carries text/thinking/toolCall blocks, a
 * {@link dev.jpi.ai.ToolResultMessage} carries text/image blocks.
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
            arguments = arguments == null ? Map.of() : Collections.unmodifiableMap(arguments);
        }
    }
}
