package dev.jpi.ai;

import java.util.List;

/**
 * An assistant message; content is text/thinking/toolCall blocks.
 *
 * <p><em>Why immutable with a PENDING state:</em> during streaming the same record
 * doubles as the "response so far" — each event replaces it with a new snapshot, so
 * anything holding an older partial (a UI, a log) can never observe later mutation.
 * <em>Why failures are well-formed messages:</em> a failed or aborted call still ends
 * in the transcript with {@code stopReason == ERROR}/{@code ABORTED} and an
 * {@code errorMessage}, which is why the loop never has to catch provider exceptions
 * and every consumer handles exactly one message shape.
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
