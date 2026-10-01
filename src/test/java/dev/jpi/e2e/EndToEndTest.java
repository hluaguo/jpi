package dev.jpi.e2e;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

import dev.jpi.ai.Content;
import dev.jpi.ai.Context;
import dev.jpi.ai.Message;
import dev.jpi.ai.StreamFn;
import dev.jpi.ai.StreamOptions;
import dev.jpi.ai.AssistantMessage;
import dev.jpi.ai.ToolResultMessage;
import dev.jpi.ai.UserMessage;
import dev.jpi.ai.providers.FixtureStreamFn;
import dev.jpi.agent.AgentLoop;
import dev.jpi.agent.AgentLoopConfig;
import dev.jpi.agent.DeterministicPruner;
import dev.jpi.json.Json;
import dev.jpi.session.SessionReader;
import dev.jpi.session.SessionRecord;
import dev.jpi.session.SessionReplayer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The end-to-end seam: a full agent task — recorded wire bytes → adapter parse →
 * loop → real tools → JSONL session — and its replay, asserted as one composition.
 * Everything here runs offline against {@code fixtures/deepseek/*} recorded by
 * {@link dev.jpi.ai.providers.RecordDeepSeekFixtures}; E2eTask throws on any
 * broken invariant, so a green run means the whole stack composed correctly.
 */
class EndToEndTest {

    @Test
    void fullAgentTaskFromRecordedWireBytes(@TempDir Path workspace) {
        E2eTask.Result result = E2eTask.run(workspace);

        // the run was real: six LLM calls, hundreds of events, recorded bytes, tokens
        assertEquals(6, result.llmCalls());
        assertTrue(result.loopEvents() > 200, "loop events: " + result.loopEvents());
        assertTrue(result.jsonlBytes() > 100_000, "session bytes: " + result.jsonlBytes());
        assertTrue(result.promptTokens() > 0 && result.completionTokens() > 0,
                "usage must come from the recorded provider chunks");
        // phase budget sanity: no phase silently dominates or vanishes
        assertTrue(result.totalMs() >= result.parseMs() + result.loopMs() + result.replayMs(),
                "total must cover all phases");
    }

    @Test
    void sessionFileIsWellFormedJsonlEnvelope(@TempDir Path workspace) throws Exception {
        E2eTask.run(workspace);

        List<String> lines = Files.readAllLines(workspace.resolve("session.jsonl"));
        assertTrue(lines.size() > 200, "recorded lines: " + lines.size());
        for (String line : lines) {
            JsonNode node = Json.readTree(line);
            assertNotNull(node, "unparsable session line");
            assertTrue(node.has("ts") && node.get("ts").isNumber(), "missing ts envelope");
            assertTrue(node.has("event") && node.get("event").isObject(), "missing event envelope");
        }
        // the recorder is a listener: reading the file back must reproduce the transcript
        List<SessionRecord> records = SessionReader.read(workspace.resolve("session.jsonl"));
        List<Message> transcript = SessionReplayer.transcript(records);
        assertEquals(13, transcript.size());
    }

    @Test
    void recordedSessionReplaysIntoEquivalentRun(@TempDir Path workspace) {
        // E2eTask re-runs the session through SessionReplayer.toProvider and throws
        // on divergence (assistant content is fixture-frozen, tool results are
        // deterministic given the re-seeded workspace); here we pin the counters
        E2eTask.Result result = E2eTask.run(workspace);
        assertEquals(13, result.liveMessages());
        assertTrue(result.replayEvents() > 200, "replay events: " + result.replayEvents());
    }

    @Test
    void prunerNeverOrphansAToolResultUnderTinyWindow(@TempDir Path workspace) {
        // a scripted provider wrapped so every context the loop actually sent is captured
        List<Context> sent = new ArrayList<>();
        StreamFn fixtures = FixtureStreamFn.scripted("chat-read-poem", "agent-turn1", "agent-turn2", "agent-turn4");
        StreamFn provider = (model, context, options) -> {
            sent.add(context);
            return fixtures.stream(model, context, options);
        };

        AgentLoop loop = new AgentLoop(provider,
                new AgentLoopConfig.Builder()
                        .transformContext(new DeterministicPruner(0.0005, 2, 200))
                        .build());
        AgentLoop.LoopResult result = loop.run(FixtureStreamFn.MODEL,
                new dev.jpi.agent.AgentContext(null, List.of(), E2eTask.tools(workspace)),
                List.of(UserMessage.of("edit poem.txt, write notes.md, verify, summarize")));

        assertEquals(dev.jpi.ai.StopReason.STOP, result.stopReason());
        assertTrue(sent.size() >= 4, "llm calls: " + sent.size());
        for (Context context : sent) {
            // the pruner may stub tool results in place, but a toolCall must never
            // lose its matching toolResult (providers reject orphans)
            for (Message message : context.messages()) {
                if (message instanceof AssistantMessage assistant) {
                    for (Content block : assistant.content()) {
                        if (block instanceof Content.ToolCall call) {
                            assertTrue(hasToolResult(context.messages(), call.id()),
                                    "orphan tool call " + call.id() + " in sent context");
                        }
                    }
                }
            }
        }
    }

    private static boolean hasToolResult(List<Message> messages, String toolCallId) {
        return messages.stream()
                .filter(ToolResultMessage.class::isInstance)
                .map(ToolResultMessage.class::cast)
                .anyMatch(tr -> tr.toolCallId().equals(toolCallId));
    }
}
