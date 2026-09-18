package dev.jpi;

import dev.jpi.ai.AssistantMessage;
import dev.jpi.ai.Content;
import dev.jpi.ai.Message;
import dev.jpi.ai.Model;
import dev.jpi.ai.StopReason;
import dev.jpi.ai.ToolResultMessage;
import dev.jpi.ai.Usage;
import dev.jpi.ai.UserMessage;

import java.util.List;
import java.util.Map;

/**
 * Shared test fixtures: the canonical message/event shapes every suite asserts
 * against, with fixed timestamps so expectations (golden files included) stay
 * byte-stable. Factories take the fields a test actually varies; everything else
 * is a deliberate default, so adding a model field does not touch every test.
 */
public final class Fixtures {

    /** The fixed timestamp stamped into every fixture. */
    public static final long TS = 1700000000000L;

    /** The canonical test model, as if declared by the Anthropic catalog. */
    public static Model model() {
        return new Model("claude-sonnet-4", "anthropic-messages", "anthropic", "http://localhost", 200000, 8192);
    }

    public static UserMessage userMessage() {
        return new UserMessage(
                List.of(new Content.Text("hello"), new Content.Image("aGVsbG8=", "image/png")),
                TS);
    }

    public static AssistantMessage assistantMessage() {
        return new AssistantMessage(
                "anthropic-messages", "anthropic", "claude-sonnet-4",
                List.of(
                        new Content.Thinking("reasoning", "sig==", false),
                        new Content.Text("hi"),
                        new Content.ToolCall("call_1", "read", Map.of("path", "a.txt"))),
                new Usage(100, 20, 80, 10, 0.3, 0.06, 0.24, 0.03),
                StopReason.TOOL_USE, null, null, TS);
    }

    public static ToolResultMessage toolResultMessage() {
        return new ToolResultMessage(
                "call_1", "read",
                List.of(new Content.Text("file body")),
                Map.of("bytes", 9), false, TS);
    }

    /** The canonical list: one message of every role. */
    public static List<Message> allMessages() {
        return List.of(userMessage(), assistantMessage(), toolResultMessage());
    }

    private Fixtures() {
    }
}
