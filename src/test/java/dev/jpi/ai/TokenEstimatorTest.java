package dev.jpi.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link TokenEstimator}. Expected numbers are hand-computed from
 * pi's heuristic: 4 chars per token, 4800 chars per image block.
 */
class TokenEstimatorTest {

    @Test
    void estimatesTextTokensByRoundingUpQuarterChars() {
        assertEquals(0, TokenEstimator.estimateTextTokens(""));
        assertEquals(1, TokenEstimator.estimateTextTokens("abcd"));
        assertEquals(2, TokenEstimator.estimateTextTokens("abcde"));
    }

    @Test
    void estimatesTextAndImageContent() {
        // 4 chars of text + one image (4800 chars) = 4804 chars -> 1201 tokens
        List<Content> content = List.of(
                new Content.Text("abcd"),
                new Content.Image("aGk=", "image/png"));

        assertEquals(1201, TokenEstimator.estimateTextAndImageContentTokens(content));
    }

    @Test
    void estimatesUserAndToolResultMessagesFromTheirContent() {
        assertEquals(1, TokenEstimator.estimateMessageTokens(new UserMessage(List.of(new Content.Text("abcd")), 0L)));
        assertEquals(1200, TokenEstimator.estimateMessageTokens(
                new ToolResultMessage("c", "read", List.of(new Content.Image("aGk=", "image/png")),
                        Map.of(), false, 0L)));
    }

    @Test
    void estimatesAssistantThinkingTextAndToolCalls() {
        AssistantMessage message = new AssistantMessage("api", "prov", "m",
                List.of(
                        new Content.Thinking("12345678"),                    // 8 chars
                        new Content.Text("1234"),                            // 4 chars
                        new Content.ToolCall("c1", "bash", Map.of("cmd", "ls"))),  // 4 + 12 JSON chars
                Usage.ZERO, StopReason.TOOL_USE, null, null, 0L);

        // (8 + 4 + 4 + 12) / 4 = 7 exactly
        assertEquals(7, TokenEstimator.estimateMessageTokens(message));
    }

    @Test
    void sumsUsageComponentsForContextTokens() {
        assertEquals(10, TokenEstimator.calculateContextTokens(new Usage(1, 2, 3, 4, 0, 0, 0, 0)));
        assertEquals(0, TokenEstimator.calculateContextTokens(Usage.ZERO));
    }

    @Test
    void anchorsOnTheLastApplicableAssistantUsage() {
        List<Message> messages = List.of(
                new UserMessage(List.of(new Content.Text("hello")), 1L),
                assistant(StopReason.STOP, new Usage(100, 0, 0, 0, 0, 0, 0, 0), 2L),
                new UserMessage(List.of(new Content.Text("worldxxxx")), 3L));   // 8 chars -> 2 tokens

        TokenEstimator.ContextUsageEstimate r = TokenEstimator.estimateContextTokens(messages);

        assertEquals(100, r.usageTokens());
        assertEquals(3, r.trailingTokens());   // "worldxxxx" is 9 chars
        assertEquals(103, r.tokens());
        assertEquals(1, r.lastUsageIndex());
    }

    @Test
    void withoutApplicableUsageEverythingIsTrailing() {
        List<Message> messages = List.of(
                new UserMessage(List.of(new Content.Text("abcdabcd")), 1L),     // 2 tokens
                assistant(StopReason.STOP, Usage.ZERO, 2L),
                new ToolResultMessage("c", "read", List.of(new Content.Text("abcd")),
                        Map.of(), false, 3L));                                   // 1 token

        TokenEstimator.ContextUsageEstimate r = TokenEstimator.estimateContextTokens(messages);

        assertEquals(0, r.usageTokens());
        assertEquals(3, r.trailingTokens());
        assertEquals(3, r.tokens());
        assertEquals(null, r.lastUsageIndex());
    }

    @Test
    void abortedUsageDoesNotAnchor() {
        List<Message> messages = List.of(
                new UserMessage(List.of(new Content.Text("abcdabcd")), 1L),     // 2 tokens
                assistant(StopReason.ABORTED, new Usage(100, 0, 0, 0, 0, 0, 0, 0), 2L));

        TokenEstimator.ContextUsageEstimate r = TokenEstimator.estimateContextTokens(messages);

        assertEquals(null, r.lastUsageIndex());
        assertEquals(2, r.tokens());
    }

    @Test
    void usageFromBeforeANewerInsertedMessageDoesNotAnchor() {
        // a compaction summary inserted later carries a newer timestamp than the
        // assistant response before it, so that response cannot describe the prefix
        List<Message> messages = List.of(
                new UserMessage(List.of(new Content.Text("abcd")), 200L),       // 1 token
                assistant(StopReason.STOP, new Usage(100, 0, 0, 0, 0, 0, 0, 0), 100L));

        TokenEstimator.ContextUsageEstimate r = TokenEstimator.estimateContextTokens(messages);

        assertEquals(null, r.lastUsageIndex());
        assertEquals(1, r.tokens());
    }

    @Test
    void theLatestApplicableAssistantWins() {
        List<Message> messages = List.of(
                new UserMessage(List.of(new Content.Text("abcd")), 1L),
                assistant(StopReason.STOP, new Usage(50, 0, 0, 0, 0, 0, 0, 0), 2L),
                new UserMessage(List.of(new Content.Text("abcd")), 3L),         // 1 token
                assistant(StopReason.STOP, new Usage(70, 0, 0, 0, 0, 0, 0, 0), 4L),
                new UserMessage(List.of(new Content.Text("abcd")), 5L));        // 1 token

        TokenEstimator.ContextUsageEstimate r = TokenEstimator.estimateContextTokens(messages);

        assertEquals(70, r.usageTokens());
        assertEquals(1, r.trailingTokens());   // only the last user trails the winning anchor
        assertEquals(3, r.lastUsageIndex());
    }

    private static AssistantMessage assistant(StopReason stopReason, Usage usage, long timestamp) {
        return new AssistantMessage("api", "prov", "m", List.of(), usage, stopReason, null, null, timestamp);
    }
}
