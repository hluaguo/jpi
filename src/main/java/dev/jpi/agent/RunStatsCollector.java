package dev.jpi.agent;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import dev.jpi.ai.AssistantMessage;
import dev.jpi.ai.CostCalculator;
import dev.jpi.ai.Message;
import dev.jpi.ai.Model;
import dev.jpi.ai.ToolResultMessage;

/**
 * A {@link Consumer<AgentEvent>} that reduces a run's event stream into
 * {@link RunStats}. Doubling as the subscribe target is the point: pass it to
 * {@code Agent.subscribe} or {@code AgentLoop.run} and read {@link #stats()} after
 * the run ends. The injectable clock keeps duration deterministic in tests.
 */
public final class RunStatsCollector implements Consumer<AgentEvent> {

    private final LongSupplier clock;
    private final Model model;

    private long startTs = -1;
    private long endTs = -1;
    private long input;
    private long output;
    private long cacheRead;
    private long cacheWrite;
    private int cacheHits;
    private double cost;
    private int messages;
    private int toolResults;

    public RunStatsCollector(LongSupplier clock, Model model) {
        this.clock = clock;
        this.model = model;
    }

    /** Real-time collector for a run against an unrated model. */
    public RunStatsCollector() {
        this(System::currentTimeMillis, null);
    }

    @Override
    public void accept(AgentEvent event) {
        if (event instanceof AgentEvent.Start) {
            startTs = clock.getAsLong();
        } else if (event instanceof AgentEvent.MessageEnd(Message message)
                && message instanceof AssistantMessage assistant) {
            accumulate(assistant);
        } else if (event instanceof AgentEvent.End(List<Message> newMessages)) {
            endTs = clock.getAsLong();
            messages = newMessages.size();
            toolResults = (int) newMessages.stream().filter(ToolResultMessage.class::isInstance).count();
        }
    }

    private void accumulate(AssistantMessage assistant) {
        var usage = CostCalculator.costOf(assistant.usage(), model);
        input += usage.input();
        output += usage.output();
        cacheRead += usage.cacheRead();
        cacheWrite += usage.cacheWrite();
        cost += usage.totalCost();
        if (usage.cacheRead() > 0) {
            cacheHits++;
        }
    }

    public RunStats stats() {
        boolean timed = startTs >= 0 && endTs >= startTs;
        return new RunStats(input, output, cacheRead, cacheWrite, cacheHits, cost,
                timed ? endTs - startTs : 0, messages, toolResults);
    }
}
