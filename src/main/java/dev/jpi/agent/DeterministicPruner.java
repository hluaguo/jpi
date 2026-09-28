package dev.jpi.agent;

import java.util.ArrayList;
import java.util.List;

import dev.jpi.ai.AssistantMessage;
import dev.jpi.ai.Content;
import dev.jpi.ai.Message;
import dev.jpi.ai.Model;
import dev.jpi.ai.ToolResultMessage;
import dev.jpi.ai.TokenEstimator;

/**
 * The deterministic context guard: when the estimated current context crosses the
 * threshold share of the model's context window, old tool results are stubbed in
 * place — oversize ones first, all of them as fallback. Recent messages are never
 * touched, and results are stubbed rather than dropped so an assistant toolCall can
 * never lose its toolResult (the loop's continue path and providers reject orphans).
 *
 * <p><em>Why an anchored estimate and not bare reported usage:</em> the last
 * response's {@link dev.jpi.ai.Usage} is the provider's truth, but it is one turn
 * stale — it can't see messages queued after it, and before the first response
 * there is no usage at all. {@link TokenEstimator#estimateContextTokens} anchors
 * on the last applicable usage and estimates only what trails it, so the guard
 * measures the context it is about to send. It converges over turns if the first
 * targeted pass does not fully fix an overflow.
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
        long contextTokens = TokenEstimator.estimateContextTokens(messages).tokens();
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
