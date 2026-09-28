package dev.jpi.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.jpi.Fixtures;
import dev.jpi.ai.AssistantMessage;
import dev.jpi.ai.Content;
import dev.jpi.ai.Message;
import dev.jpi.ai.Model;
import dev.jpi.ai.StopReason;
import dev.jpi.ai.ToolResultMessage;
import dev.jpi.ai.Usage;
import dev.jpi.ai.UserMessage;
import dev.jpi.ai.providers.ScriptedProvider;

/**
 * Pruning invariants: the trigger is the anchored estimate (reported usage when
 * applicable, heuristics before the first response or after an aborted one), oversize tool outputs go first, the recent tail is untouchable,
 * and an assistant toolCall is never separated from its toolResult — stubbed in
 * place, not dropped.
 */
class DeterministicPrunerTest {

    private static final Model MODEL = new Model("m", "anthropic-messages", "anthropic", "http://x", 1000, 100);
    private static final long TS = Fixtures.TS;

    private static final DeterministicPruner PRUNER = new DeterministicPruner(0.5, 2, 1000);

    /** user → assistant(toolCall, big usage) → toolResult(5000 chars) → user → assistant(big usage). */
    private static List<Message> conversation() {
        return List.of(
                new UserMessage(List.of(new Content.Text("go")), TS),
                new AssistantMessage("a", "p", "m",
                        List.of(new Content.ToolCall("c1", "echo", Map.of())),
                        usage(600), StopReason.TOOL_USE, null, null, TS),
                new ToolResultMessage("c1", "echo",
                        List.of(new Content.Text("x".repeat(5000))), Map.of(), false, TS),
                new UserMessage(List.of(new Content.Text("continue")), TS),
                new AssistantMessage("a", "p", "m",
                        List.of(new Content.Text("done")),
                        usage(600), StopReason.STOP, null, null, TS));
    }

    private static Usage usage(long totalTokens) {
        return new Usage(totalTokens, 0, 0, 0, 0, 0, 0, 0);
    }

    private static void assertPairsIntact(List<Message> messages) {
        Set<String> toolCalls = new HashSet<>();
        Set<String> results = new HashSet<>();
        for (Message message : messages) {
            if (message instanceof AssistantMessage assistant) {
                assistant.content().stream()
                        .filter(Content.ToolCall.class::isInstance)
                        .map(Content.ToolCall.class::cast)
                        .forEach(call -> toolCalls.add(call.id()));
            } else if (message instanceof ToolResultMessage result) {
                results.add(result.toolCallId());
            }
        }
        assertEquals(toolCalls, results, "every toolCall needs its toolResult and vice versa");
    }

    @Test
    void underBudgetOrWithoutProviderUsageReturnsMessagesUnchanged() {
        List<Message> messages = conversation();

        // no applicable usage yet → everything is estimated, and tiny stays tiny
        List<Message> noUsage = List.of(
                new UserMessage(List.of(new Content.Text("go")), TS),
                new AssistantMessage("a", "p", "m", List.of(new Content.Text("early")),
                        Usage.ZERO, StopReason.STOP, null, null, TS));
        assertSame(noUsage, PRUNER.transform(MODEL, noUsage));

        List<Message> small = List.of(
                new UserMessage(List.of(new Content.Text("go")), TS),
                new AssistantMessage("a", "p", "m", List.of(new Content.Text("hi")),
                        usage(100), StopReason.STOP, null, null, TS));
        assertSame(small, PRUNER.transform(MODEL, small));
    }

    @Test
    void prunesOnEstimateBeforeAnyApplicableUsageExists() {
        // a continued transcript: the only reported usage is from an aborted
        // response (never an anchor), so the guard must act on the estimate —
        // the 5000-char tool result alone (~1250 tokens) overflows the 500 budget
        List<Message> messages = new ArrayList<>(List.of(
                new UserMessage(List.of(new Content.Text("go")), TS),
                new AssistantMessage("a", "p", "m",
                        List.of(new Content.ToolCall("c1", "echo", Map.of())),
                        usage(600), StopReason.ABORTED, null, null, TS),
                new ToolResultMessage("c1", "echo",
                        List.of(new Content.Text("x".repeat(5000))), Map.of(), false, TS),
                new UserMessage(List.of(new Content.Text("continue")), TS),
                new AssistantMessage("a", "p", "m", List.of(new Content.Text("?")),
                        Usage.ZERO, StopReason.STOP, null, null, TS),
                new UserMessage(List.of(new Content.Text("again")), TS)));

        List<Message> pruned = PRUNER.transform(MODEL, messages);

        assertNotSame(messages, pruned);
        assertPairsIntact(pruned);
        ToolResultMessage stubbed = (ToolResultMessage) pruned.get(2);
        assertTrue(((Content.Text) stubbed.content().get(0)).text()
                .startsWith("[pruned tool result: "), "oversize result is stubbed");
    }

    @Test
    void overBudgetPrunesOldToolResultsInPlace() {
        List<Message> pruned = PRUNER.transform(MODEL, new ArrayList<>(conversation()));

        assertEquals(conversation().size(), pruned.size(), "messages are stubbed, never dropped");
        assertPairsIntact(pruned);

        ToolResultMessage stubbed = (ToolResultMessage) pruned.get(2);
        assertEquals("[pruned tool result: 5000 chars]", ((Content.Text) stubbed.content().get(0)).text());
        assertTrue(stubbed.toolCallId().equals("c1"));
    }

    @Test
    void recentTailIsNeverTouchedEvenWhenOversize() {
        // tail = last 2 messages; put the oversize tool result there by using keepLast=3
        DeterministicPruner pruner = new DeterministicPruner(0.5, 3, 1000);
        List<Message> pruned = pruner.transform(MODEL, new ArrayList<>(conversation()));

        ToolResultMessage tail = (ToolResultMessage) pruned.get(2);
        assertEquals(5000, ((Content.Text) tail.content().get(0)).text().length());
        assertPairsIntact(pruned);
    }

    @Test
    void oversizeResultsGoFirstAndSmallsSurvive() {
        // two old tool results: one small (50 chars), one oversize (5000 chars)
        List<Message> messages = List.of(
                new UserMessage(List.of(new Content.Text("go")), TS),
                new AssistantMessage("a", "p", "m",
                        List.of(
                                new Content.ToolCall("c0", "echo", Map.of()),
                                new Content.ToolCall("c1", "echo", Map.of())),
                        usage(600), StopReason.TOOL_USE, null, null, TS),
                new ToolResultMessage("c0", "echo",
                        List.of(new Content.Text("y".repeat(50))), Map.of(), false, TS),
                new ToolResultMessage("c1", "echo",
                        List.of(new Content.Text("x".repeat(5000))), Map.of(), false, TS),
                new UserMessage(List.of(new Content.Text("continue")), TS),
                new AssistantMessage("a", "p", "m", List.of(new Content.Text("done")),
                        usage(600), StopReason.STOP, null, null, TS));

        List<Message> pruned = PRUNER.transform(MODEL, messages);

        // the small old result survives: oversize-only pass is trusted to fix the overflow
        assertEquals(50, ((Content.Text) ((ToolResultMessage) pruned.get(2)).content().get(0)).text().length());
        assertEquals("[pruned tool result: 5000 chars]",
                ((Content.Text) ((ToolResultMessage) pruned.get(3)).content().get(0)).text());
        assertPairsIntact(pruned);
    }

    @Test
    void allOldToolResultsStubbedWhenNothingIsOversize() {
        List<Message> messages = new ArrayList<>(conversation());
        messages.set(2, new ToolResultMessage("c1", "echo",
                List.of(new Content.Text("z".repeat(500))), Map.of(), false, TS)); // under oversize limit

        List<Message> pruned = PRUNER.transform(MODEL, messages);

        ToolResultMessage stubbed = (ToolResultMessage) pruned.get(2);
        assertEquals("[pruned tool result: 500 chars]", ((Content.Text) stubbed.content().get(0)).text());
        assertPairsIntact(pruned);
    }

    @Test
    void loopPrunesOnlyTheProviderViewNotTheTranscript() {
        ScriptedProvider provider = ScriptedProvider.builder().text("ok").build();
        AgentLoopConfig config = AgentLoopConfig.builder()
                .transformContext((model, messages) -> List.of()) // extreme: nothing goes to the provider
                .build();

        AgentLoop.LoopResult result = new AgentLoop(provider, config).run(
                Fixtures.model(),
                new AgentContext(null, List.of(), List.of()),
                List.of(UserMessage.of("go")));

        // the provider saw the pruned view…
        assertEquals(List.of(), provider.calls().get(0).context().messages());
        // …while the transcript kept everything
        assertEquals(2, result.messages().size());
    }

    @Test
    void prunerReceivesTheModelForBudgetDecisions() {
        Set<Integer> seenWindows = new HashSet<>();
        AgentLoopConfig config = AgentLoopConfig.builder()
                .transformContext((model, messages) -> {
                    seenWindows.add(model.contextWindow());
                    return messages;
                })
                .build();

        new AgentLoop(ScriptedProvider.builder().text("ok").build(), config).run(
                Fixtures.model(), new AgentContext(null, List.of(), List.of()),
                List.of(UserMessage.of("go")));

        assertEquals(Set.of(Fixtures.model().contextWindow()), seenWindows);
    }
}
