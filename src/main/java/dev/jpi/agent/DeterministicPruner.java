package dev.jpi.agent;

import java.util.ArrayList;
import java.util.List;

import dev.jpi.ai.AssistantMessage;
import dev.jpi.ai.Content;
import dev.jpi.ai.Message;
import dev.jpi.ai.Model;
import dev.jpi.ai.ToolResultMessage;

/**
 * The deterministic context guard: when the last provider-reported usage crosses the
 * threshold share of the model's context window, old tool results are stubbed in
 * place — oversize ones first, all of them as fallback. Recent messages are never
 * touched, and results are stubbed rather than dropped so an assistant toolCall can
 * never lose its toolResult (the loop's continue path and providers reject orphans).
 *
 * <p><em>Why the trigger is reported usage, not an estimate:</em> token counts are
 * the provider's truth; jpi does not count characters. The consequence is that the
 * guard acts on the last known context size — the standard pre-flight signal — and
 * converges over turns if the first targeted pass does not fully fix an overflow.
 */
public final class DeterministicPruner implements AgentLoopConfig.ContextTransformer {

    private static final String STUB_PREFIX = "[pruned tool result: ";

    private final double threshold;
    private final int keepLastMessages;
    private final int oversizeToolResultChars;

    /**
     * @param threshold                prune when reported usage exceeds this share of the context window
     * @param keepLastMessages         the most recent N messages are never modified
     * @param oversizeToolResultChars  results above this char count are pruned first
     */
    public DeterministicPruner(double threshold, int keepLastMessages, int oversizeToolResultChars) {
        this.threshold = threshold;
        this.keepLastMessages = keepLastMessages;
        this.oversizeToolResultChars = oversizeToolResultChars;
    }

    @Override
    public List<Message> transform(Model model, List<Message> messages) {
        long contextTokens = latestReportedUsage(messages);
        if (contextTokens <= threshold * model.contextWindow()) {
            return messages;
        }
        int protectedFrom = Math.max(0, messages.size() - keepLastMessages);

        List<Message> pruned = stubToolResults(messages, protectedFrom, oversizeToolResultChars, true);
        if (pruned == null) {
            pruned = stubToolResults(messages, protectedFrom, oversizeToolResultChars, false);
        }
        return pruned != null ? pruned : messages;
    }

    /** The total tokens of the most recent assistant response; 0 before the first response. */
    private static long latestReportedUsage(List<Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof AssistantMessage assistant) {
                return assistant.usage().totalTokens();
            }
        }
        return 0;
    }

    /**
     * Stubs old tool results, oversize-only when asked; returns null when nothing
     * matched (signaling the caller to fall back to stubbing all old results).
     */
    private static List<Message> stubToolResults(List<Message> messages, int protectedFrom,
                                                 int oversizeLimit, boolean oversizeOnly) {
        List<Message> result = null;
        for (int i = 0; i < protectedFrom; i++) {
            if (messages.get(i) instanceof ToolResultMessage toolResult && matches(toolResult, oversizeLimit, oversizeOnly)) {
                if (result == null) {
                    result = new ArrayList<>(messages);
                }
                int chars = contentChars(toolResult);
                result.set(i, new ToolResultMessage(toolResult.toolCallId(), toolResult.toolName(),
                        List.of(new Content.Text(STUB_PREFIX + chars + " chars]")),
                        toolResult.details(), toolResult.isError(), toolResult.timestamp()));
            }
        }
        return result;
    }

    private static boolean matches(ToolResultMessage toolResult, int oversizeLimit, boolean oversizeOnly) {
        return !oversizeOnly || contentChars(toolResult) > oversizeLimit;
    }

    private static int contentChars(ToolResultMessage toolResult) {
        int chars = 0;
        for (Content block : toolResult.content()) {
            if (block instanceof Content.Text text) {
                chars += text.text().length();
            }
        }
        return chars;
    }
}
