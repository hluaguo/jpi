package dev.jpi.ai;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Renders messages as readable text for logs and demos — the transcript as a
 * human scans it, not as the model receives it.
 *
 * <p>{@link #contentText(List)} is the Java port of pi's {@code contentText}
 * (pi-ai/utils/text.ts): join text blocks, drop everything else. The
 * {@code render} methods are jpi-native formatting; their output is for humans
 * only and never sent to a provider.
 */
public final class Transcripts {

    /** Extract and join the text blocks from {@code blocks}, separated by newlines. */
    public static String contentText(List<Content> blocks) {
        return contentText(blocks, "\n");
    }

    /** As {@link #contentText(List)}, with a custom separator. */
    public static String contentText(List<Content> blocks, String separator) {
        return blocks.stream()
                .filter(Content.Text.class::isInstance)
                .map(block -> ((Content.Text) block).text())
                .collect(Collectors.joining(separator));
    }

    /** Render one message as readable lines: {@code [user] …}, {@code [assistant] …}, {@code [tool name] …}. */
    public static String render(Message message) {
        return switch (message) {
            case UserMessage user -> "[user] " + contentText(user.content());
            case AssistantMessage assistant -> renderAssistant(assistant);
            case ToolResultMessage result -> "[tool " + result.toolName() + "] "
                    + (result.isError() ? "✗ " : "") + contentText(result.content());
        };
    }

    /** Render a transcript: each message's lines, blank lines between messages. */
    public static String render(List<? extends Message> messages) {
        return messages.stream()
                .map(Transcripts::render)
                .collect(Collectors.joining("\n\n"));
    }

    /*
     * Each block gets its own line so a stream of these lines reads chronologically:
     * thinking before text, tool calls after the text that announces them. A failed
     * call renders its error instead of blocks — errors are the message then.
     */
    private static String renderAssistant(AssistantMessage message) {
        if (message.errorMessage() != null) {
            return "[error] " + message.errorMessage();
        }
        return message.content().stream()
                .<String>map(block -> switch (block) {
                    case Content.Thinking thinking -> "[thinking] " + thinking.thinking();
                    case Content.Text text -> "[assistant] " + text.text();
                    case Content.ToolCall call -> "[call " + call.name() + "] " + call.arguments();
                    case Content.Image image -> "[image]";
                })
                .collect(Collectors.joining("\n"));
    }

    private Transcripts() {
    }
}
