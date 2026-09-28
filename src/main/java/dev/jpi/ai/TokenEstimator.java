package dev.jpi.ai;

import java.util.List;

import dev.jpi.json.Json;

/**
 * Heuristic token estimation, ported from pi's pi-ai/utils/estimate.
 *
 * <p><em>Why heuristics at all:</em> the exact token count arrives only with a
 * response ({@link Usage}), which is always one turn too late — pruning,
 * overflow guards and context budgeting need a number <em>before</em> the next
 * call. 4 chars per token (4800 per image) is deliberately crude: it only has
 * to be right within a factor that decisions with safety margins tolerate.
 */
public final class TokenEstimator {

    private static final int CHARS_PER_TOKEN = 4;
    private static final int ESTIMATED_IMAGE_CHARS = 4800;

    /** The context tokens a response consumed: the usage components summed. */
    public static long calculateContextTokens(Usage usage) {
        return usage.totalTokens();
    }

    /** Estimate tokens for plain text: a quarter of its chars, rounded up. */
    public static long estimateTextTokens(String text) {
        return (long) Math.ceil(text.length() / (double) CHARS_PER_TOKEN);
    }

    /** Estimate tokens for text/image content: text by length, every other block as one image. */
    public static long estimateTextAndImageContentTokens(List<Content> content) {
        long chars = 0;
        for (Content block : content) {
            chars += block instanceof Content.Text text ? text.text().length() : ESTIMATED_IMAGE_CHARS;
        }
        return (long) Math.ceil(chars / (double) CHARS_PER_TOKEN);
    }

    /** Estimate tokens for one message of any role. */
    public static long estimateMessageTokens(Message message) {
        if (message instanceof UserMessage user) {
            return estimateTextAndImageContentTokens(user.content());
        }
        if (message instanceof ToolResultMessage result) {
            return estimateTextAndImageContentTokens(result.content());
        }
        AssistantMessage assistant = (AssistantMessage) message;
        long chars = 0;
        for (Content block : assistant.content()) {
            if (block instanceof Content.Text text) {
                chars += text.text().length();
            } else if (block instanceof Content.Thinking thinking) {
                chars += thinking.thinking().length();
            } else if (block instanceof Content.ToolCall call) {
                chars += call.name().length() + safeJsonStringify(call.arguments()).length();
            } else {
                chars += ESTIMATED_IMAGE_CHARS;
            }
        }
        return (long) Math.ceil(chars / (double) CHARS_PER_TOKEN);
    }

    /*
     * Mirrors pi's safeJsonStringify: an unserializable argument must not take
     * estimation down — it counts as a fixed blob instead.
     */
    private static String safeJsonStringify(Object value) {
        try {
            String json = Json.write(value);
            return json == null ? "undefined" : json;
        } catch (Exception e) {
            return "[unserializable]";
        }
    }

    /** An anchored context estimate: reported usage plus what has accrued since. */
    public record ContextUsageEstimate(long tokens, long usageTokens, long trailingTokens, Integer lastUsageIndex) {
    }

    /*
     * Port of pi's estimateContextTokens: prefer a real Usage over heuristics. The
     * anchor is the last assistant message whose usage can describe the current
     * prefix — its timestamp must not be older than any message before it (a later
     * insertion like a compaction summary invalidates it), and aborted/error
     * responses never anchor because their usage describes a partial turn. Without
     * an anchor everything is heuristic and all of it is trailing.
     */
    public static ContextUsageEstimate estimateContextTokens(List<Message> messages) {
        long latestPrefixTimestamp = Long.MIN_VALUE;
        Usage anchorUsage = null;
        int anchorIndex = -1;
        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);
            if (message instanceof AssistantMessage assistant
                    && assistant.timestamp() >= latestPrefixTimestamp
                    && assistant.stopReason() != StopReason.ABORTED
                    && assistant.stopReason() != StopReason.ERROR
                    && calculateContextTokens(assistant.usage()) > 0) {
                anchorUsage = assistant.usage();
                anchorIndex = i;
            }
            latestPrefixTimestamp = Math.max(latestPrefixTimestamp, timestampOf(message));
        }

        if (anchorUsage != null) {
            long usageTokens = calculateContextTokens(anchorUsage);
            long trailingTokens = 0;
            for (int i = anchorIndex + 1; i < messages.size(); i++) {
                trailingTokens += estimateMessageTokens(messages.get(i));
            }
            return new ContextUsageEstimate(usageTokens + trailingTokens, usageTokens, trailingTokens, anchorIndex);
        }
        long tokens = 0;
        for (Message message : messages) {
            tokens += estimateMessageTokens(message);
        }
        return new ContextUsageEstimate(tokens, 0, tokens, null);
    }

    private static long timestampOf(Message message) {
        if (message instanceof UserMessage user) {
            return user.timestamp();
        }
        if (message instanceof ToolResultMessage result) {
            return result.timestamp();
        }
        return ((AssistantMessage) message).timestamp();
    }

    private TokenEstimator() {
    }
}
