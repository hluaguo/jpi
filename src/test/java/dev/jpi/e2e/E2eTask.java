package dev.jpi.e2e;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Map;
import java.util.function.Consumer;
import dev.jpi.util.CancellationToken;

import dev.jpi.agent.AgentContext;
import dev.jpi.agent.AgentEvent;
import dev.jpi.agent.AgentLoop;
import dev.jpi.agent.AgentLoopConfig;
import dev.jpi.agent.AgentTool;
import dev.jpi.agent.DeterministicPruner;
import dev.jpi.agent.RunStats;
import dev.jpi.agent.RunStatsCollector;
import dev.jpi.ai.AssistantMessage;
import dev.jpi.ai.Content;
import dev.jpi.ai.Message;
import dev.jpi.ai.Model;
import dev.jpi.ai.StopReason;
import dev.jpi.ai.ToolResultMessage;
import dev.jpi.ai.UserMessage;
import dev.jpi.ai.providers.FixtureStreamFn;
import dev.jpi.session.SessionReader;
import dev.jpi.session.SessionRecord;
import dev.jpi.session.SessionReplayer;
import dev.jpi.session.SessionRecorder;
import dev.jpi.tools.BashTool;
import dev.jpi.tools.EditTool;
import dev.jpi.tools.ReadTool;
import dev.jpi.tools.WriteTool;

/**
 * The end-to-end workload: one deterministic agent task composing every layer of
 * jpi — real recorded wire bytes through the adapter parse path, the agent loop
 * with the real starter tools (exact/fuzzy edit, parallel write batch, real bash
 * subprocesses), per-event JSONL session recording, the deterministic pruner,
 * usage/cost stats — then session read-back and a full replay that re-runs the
 * task through a recorded-session provider.
 *
 * <p>JUnit-free and directory-parameterized so the offline tests
 * ({@code EndToEndTest}) and the standalone bench ({@code .auto/E2eBench}) drive
 * the exact same scenario. Every invariant violation throws: a benchmark number
 * from a run that silently misbehaved is worthless.
 */
public final class E2eTask {

    /** The poem the recorded read fixture expects in poem.txt (the agent reads it back). */
    static final String POEM = "The moon is a lantern.\nThe sea is a drum.\nThe night goes home.\n";

    /** One run's outcome: phase timings plus the counters that prove the run was real. */
    public record Result(
            long parseMs,
            long loopMs,
            long replayMs,
            long totalMs,
            int llmCalls,
            int loopEvents,
            int replayEvents,
            long jsonlBytes,
            int liveMessages,
            long promptTokens,
            long completionTokens,
            long cacheReadTokens) {
    }

    /**
     * Runs the full task in {@code workspace} (created if absent; seeded deterministically).
     * Throws on any invariant violation.
     */
    public static Result run(Path workspace) {
        long t0 = System.nanoTime();
        try {
            Files.createDirectories(workspace);
            Files.writeString(workspace.resolve("poem.txt"), POEM);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        // ---- phase 1: wire parse — the largest fixture through the real adapter chain
        long p0 = System.nanoTime();
        AtomicInteger parsedEvents = new AtomicInteger();
        AssistantMessage parsed = feed(parsedEvents, "chat-long").result().join();
        if (parsed.stopReason() != StopReason.STOP || parsedEvents.get() < 300) {
            throw new IllegalStateException("parse phase: unexpected stream outcome");
        }
        long parseMs = (System.nanoTime() - p0) / 1_000_000;

        // ---- phase 2: the agent task — six scripted LLM calls over the real tools
        Path sessionFile = workspace.resolve("session.jsonl");
        List<Message> live;
        long loopMs;
        int loopEvents;
        RunStats stats;
        try (SessionRecorder recorder = new SessionRecorder(sessionFile)) {
            AtomicInteger events = new AtomicInteger();
            RunStatsCollector statsCollector = new RunStatsCollector();
            Consumer<AgentEvent> listener = event -> {
                events.incrementAndGet();
                statsCollector.accept(event);
                recorder.accept(event);
            };

            AgentLoop loop = new AgentLoop(
                    FixtureStreamFn.scripted("chat-read-poem", "chat-multi-tool",
                            "agent-turn1", "agent-turn2", "agent-turn3", "agent-turn4"),
                    new AgentLoopConfig.Builder()
                            .toolExecution(AgentLoopConfig.ToolExecution.PARALLEL)
                            .transformContext(new DeterministicPruner(0.0005, 2, 200))
                            .build());

            long l0 = System.nanoTime();
            AgentLoop.LoopResult result = loop.run(FixtureStreamFn.MODEL,
                    new AgentContext(null, List.of(), tools(workspace)),
                    List.of(UserMessage.of("Show me poem.txt, create a.txt and b.txt, "
                            + "create notes.md with alpha/beta/gamma, verify it with bash, then summarize.")),
                    listener);
            loopMs = (System.nanoTime() - l0) / 1_000_000;
            live = result.messages();
            loopEvents = events.get();
            stats = statsCollector.stats();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        // seed AFTER the run so a later workspace can be inspected cleanly: poem.txt
        // must exist BEFORE the run (the recorded first call reads it) — create it up front

        // invariants: the task actually did what the recorded session describes
        if (live.size() != 1 + 6 + 6) { // prompt + 6 assistants + 6 tool results (one batch has two)
            throw new IllegalStateException("live transcript shape: " + live.size() + " messages");
        }
        assertFileEquals(workspace, "poem.txt", POEM);
        assertFileEquals(workspace, "a.txt", "one\n");
        assertFileEquals(workspace, "b.txt", "two\n");
        assertFileEquals(workspace, "notes.md", "alpha\nbeta\ngamma\n");
        AssistantMessage last = (AssistantMessage) live.get(live.size() - 1);
        if (last.stopReason() != StopReason.STOP || last.content().isEmpty()
                || !(last.content().get(0) instanceof Content.Text)) {
            throw new IllegalStateException("task did not end with a text STOP turn");
        }

        // ---- phase 3: session read-back + full replay of the recorded session
        long r0 = System.nanoTime();
        List<SessionRecord> records;
        try {
            records = SessionReader.read(sessionFile);
            long jsonlBytes = Files.size(sessionFile);

            List<Message> replayedTranscript = SessionReplayer.transcript(records);
            if (replayedTranscript.size() != live.size()) {
                throw new IllegalStateException("replay transcript size " + replayedTranscript.size());
            }

            AgentLoop replayLoop = new AgentLoop(
                    SessionReplayer.toProvider(records),
                    new AgentLoopConfig.Builder()
                            .toolExecution(AgentLoopConfig.ToolExecution.PARALLEL)
                            .transformContext(new DeterministicPruner(0.0005, 2, 200))
                            .build());
            AtomicInteger replayEvents = new AtomicInteger();
            AgentLoop.LoopResult replayRun = replayLoop.run(FixtureStreamFn.MODEL,
                    new AgentContext(null, List.of(), tools(workspace)),
                    List.of(UserMessage.of("Show me poem.txt, create a.txt and b.txt, "
                            + "create notes.md with alpha/beta/gamma, verify it with bash, then summarize.")),
                    event -> replayEvents.incrementAndGet());

            // a recorded session must re-run into the same outcome: the assistant
            // turns are fixture-frozen, the tool results are deterministic given
            // the re-seeded workspace
            if (replayRun.messages().size() != live.size()) {
                throw new IllegalStateException("replay run shape " + replayRun.messages().size());
            }
            for (int i = 0; i < live.size(); i++) {
                Message expected = live.get(i);
                Message actual = replayRun.messages().get(i);
                if (expected.getClass() != actual.getClass() || !sameContent(expected, actual)) {
                    throw new IllegalStateException("replay diverged at message " + i);
                }
            }
            long replayMs = (System.nanoTime() - r0) / 1_000_000;

            return new Result(parseMs, loopMs, replayMs,
                    (System.nanoTime() - t0) / 1_000_000,
                    6, loopEvents, replayEvents.get(), jsonlBytes, live.size(),
                    stats.inputTokens(), stats.outputTokens(), stats.cacheReadTokens());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The starter toolset bound to a session directory: relative paths and shell
     * commands resolve against {@code cwd}, exactly what an agent harness does for
     * a workspace. The recorded tool-call arguments stay verbatim on the wire; the
     * binding is the harness's job, not a rewrite of what the model sent.
     */
    static List<AgentTool> tools(Path cwd) {
        return List.of(
                cwdBound(new ReadTool(), cwd),
                cwdBound(new WriteTool(), cwd),
                cwdBound(new EditTool(), cwd),
                cwdBound(new BashTool(10), cwd));
    }

    private static AgentTool cwdBound(AgentTool delegate, Path cwd) {
        return new AgentTool() {
            @Override public String name() { return delegate.name(); }
            @Override public String description() { return delegate.description(); }
            @Override public Map<String, Object> parameters() { return delegate.parameters(); }
            @Override public AgentLoopConfig.ToolExecution executionMode() { return delegate.executionMode(); }
            @Override
            public dev.jpi.agent.AgentToolResult execute(String toolCallId, Map<String, Object> args,
                                                         CancellationToken signal,
                                                         Consumer<Map<String, Object>> onUpdate) {
                Map<String, Object> bound = new java.util.LinkedHashMap<>(args);
                if (args.get("path") instanceof String path) {
                    bound.put("path", cwd.resolve(path).toString());
                }
                if (args.get("command") instanceof String command) {
                    bound.put("command", "cd '" + cwd + "' && " + command);
                }
                return delegate.execute(toolCallId, bound, signal, onUpdate);
            }
        };
    }

    /** Feeds one fixture through the real parser chain, counting events. */
    private static dev.jpi.ai.AssistantMessageEventStream feed(AtomicInteger counter, String fixture) {
        counter.set(0);
        var out = FixtureStreamFn.feed(FixtureStreamFn.MODEL, FixtureStreamFn.lines(fixture));
        // count by draining: the stream is already terminal, iteration is cheap
        for (var ignored : out) {
            counter.incrementAndGet();
        }
        return out;
    }

    private static void assertFileEquals(Path workspace, String name, String expected) {
        try {
            String actual = Files.readString(workspace.resolve(name));
            if (!expected.equals(actual)) {
                throw new IllegalStateException(name + " = " + actual + ", expected " + expected);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(name + " missing", e);
        }
    }

    /** Equality of the message payload a replay must reproduce — timestamps excluded. */
    private static boolean sameContent(Message a, Message b) {
        if (a instanceof AssistantMessage ma && b instanceof AssistantMessage mb) {
            return ma.content().equals(mb.content()) && ma.stopReason() == mb.stopReason();
        }
        if (a instanceof ToolResultMessage ta && b instanceof ToolResultMessage tb) {
            return ta.toolCallId().equals(tb.toolCallId())
                    && ta.toolName().equals(tb.toolName())
                    && ta.content().equals(tb.content())
                    && ta.isError() == tb.isError();
        }
        if (a instanceof UserMessage ua && b instanceof UserMessage ub) {
            return ua.content().equals(ub.content());
        }
        return false;
    }

    private E2eTask() {
    }
}
