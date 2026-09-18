package dev.jpi.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.jpi.Fixtures;
import dev.jpi.ai.AssistantMessage;
import dev.jpi.ai.Content;
import dev.jpi.ai.Message;
import dev.jpi.ai.Model;
import dev.jpi.ai.Model.Cost;
import dev.jpi.ai.StopReason;
import dev.jpi.ai.Usage;
import dev.jpi.ai.UserMessage;
import dev.jpi.ai.providers.ScriptedProvider;

/** Golden totals over scripted event streams; all token counts are provider-reported. */
class RunStatsTest {

    private static final Model COSTED = new Model("claude-sonnet-4", "anthropic-messages", "anthropic",
            "http://localhost", 200000, 8192, new Cost(3.0, 15.0, 0.30, 3.75));

    private static AssistantMessage assistantWith(Usage usage) {
        return new AssistantMessage("anthropic-messages", "anthropic", "claude-sonnet-4",
                List.of(new Content.Text("hi")), usage, StopReason.STOP, null, null, Fixtures.TS);
    }

    @Test
    void reducesScriptedEventsToGoldenTotals() {
        long[] now = {1_000_000};
        RunStatsCollector collector = new RunStatsCollector(() -> now[0], COSTED);

        collector.accept(new AgentEvent.Start());
        now[0] += 42;
        collector.accept(new AgentEvent.MessageEnd(assistantWith(
                new Usage(1_000_000, 500_000, 200_000, 100_000, 0, 0, 0, 0))));
        collector.accept(new AgentEvent.MessageEnd(assistantWith(
                new Usage(1_000_000, 0, 200_000, 0, 0, 0, 0, 0))));
        collector.accept(new AgentEvent.End(Fixtures.allMessages()));

        RunStats stats = collector.stats();
        assertEquals(2_000_000, stats.inputTokens());
        assertEquals(500_000, stats.outputTokens());
        assertEquals(400_000, stats.cacheReadTokens());
        assertEquals(100_000, stats.cacheWriteTokens());
        assertEquals(2, stats.cacheHits());
        // worked example: 3.0 + 7.5 + 0.06 + 0.375 per response, second response input-only
        assertEquals(10.935 + 3.06, stats.cost(), 1e-9);
        assertEquals(42, stats.durationMs());
        assertEquals(3, stats.messages());
        assertEquals(1, stats.toolResults());
    }

    @Test
    void integratesWithTheLoopOverScriptedProvider() {
        ScriptedProvider provider = ScriptedProvider.builder()
                .text("hello", new Usage(1_000_000, 500_000, 200_000, 100_000, 0, 0, 0, 0))
                .build();
        RunStatsCollector collector = new RunStatsCollector(() -> 0L, COSTED);

        new AgentLoop(provider).run(Fixtures.model(),
                new AgentContext(null, List.of(), List.of()),
                List.of(UserMessage.of("go")), collector);

        RunStats stats = collector.stats();
        assertEquals(1_000_000, stats.inputTokens());
        assertEquals(500_000, stats.outputTokens());
        assertEquals(1, stats.cacheHits());
        assertEquals(10.935, stats.cost(), 1e-9);
        assertEquals(2, stats.messages());
        assertEquals(0, stats.toolResults());
    }

    @Test
    void messagesWithZeroCacheReadAreNotCacheHits() {
        RunStatsCollector collector = new RunStatsCollector(() -> 0L, COSTED);
        collector.accept(new AgentEvent.MessageEnd(assistantWith(
                new Usage(10, 20, 0, 0, 0, 0, 0, 0))));

        assertEquals(0, collector.stats().cacheHits());
        assertEquals(10, collector.stats().inputTokens());
    }
}
