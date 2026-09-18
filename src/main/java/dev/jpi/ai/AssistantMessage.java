package dev.jpi.ai;

import java.util.List;

/**
 * An assistant message; content is text/thinking/toolCall blocks. A message with
 * {@code stopReason == PENDING} is the partial "response so far" carried by streaming
 * events. Failures produce a well-formed message with {@code stopReason == ERROR} or
 * {@code ABORTED} and a non-null {@code errorMessage}.
 */
public record AssistantMessage(
        String api,
        String provider,
        String model,
        List<Content> content,
        Usage usage,
        StopReason stopReason,
        String errorMessage,
        long timestamp) implements Message {

    public AssistantMessage {
        content = content == null ? List.of() : List.copyOf(content);
        usage = usage == null ? Usage.ZERO : usage;
        stopReason = stopReason == null ? StopReason.PENDING : stopReason;
    }

    /** An empty pending partial for the given model. */
    public static AssistantMessage pending(Model model) {
        return new AssistantMessage(model.api(), model.provider(), model.id(),
                List.of(), Usage.ZERO, StopReason.PENDING, null, System.currentTimeMillis());
    }

    public AssistantMessage withContent(List<Content> content) {
        return new AssistantMessage(api, provider, model, content, usage, stopReason, errorMessage, timestamp);
    }

    public AssistantMessage withStopReason(StopReason stopReason) {
        return new AssistantMessage(api, provider, model, content, usage, stopReason, errorMessage, timestamp);
    }

    public AssistantMessage withUsage(Usage usage) {
        return new AssistantMessage(api, provider, model, content, usage, stopReason, errorMessage, timestamp);
    }

    public AssistantMessage withErrorMessage(String errorMessage) {
        return new AssistantMessage(api, provider, model, content, usage, stopReason, errorMessage, timestamp);
    }
}
