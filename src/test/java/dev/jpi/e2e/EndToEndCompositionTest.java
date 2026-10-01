package dev.jpi.e2e;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import dev.jpi.ai.AssistantMessage;
import dev.jpi.ai.Content;
import dev.jpi.ai.Message;
import dev.jpi.ai.StopReason;
import dev.jpi.ai.StreamFn;
import dev.jpi.ai.ToolResultMessage;
import dev.jpi.ai.UserMessage;
import dev.jpi.ai.providers.FixtureStreamFn;
import dev.jpi.ai.providers.ScriptedProvider;
import dev.jpi.agent.Agent;
import dev.jpi.agent.AgentEvent;
import dev.jpi.agent.AgentLoop;
import dev.jpi.agent.AgentLoopConfig;
import dev.jpi.session.SessionReader;
import dev.jpi.session.SessionRecord;
import dev.jpi.session.SessionReplayer;
import dev.jpi.session.SessionRecorder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Composition seams beyond the happy path: the stateful {@link Agent} wrapper
 * with queued steering, the truncated-tool-call failure path inside a full loop,
 * and one session file spanning multiple runs. Same rule as
 * {@link EndToEndTest}: real recorded wire bytes or the canonical scripted
 * backbone, real tools, offline, deterministic.
 */
class EndToEndCompositionTest {

    @Test
    void steeringQueueInjectsBetweenTurns(@TempDir Path workspace) {
        Agent agent = Agent.builder()
                .streamFn(FixtureStreamFn.scripted("agent-turn1", "agent-turn2", "agent-turn3", "agent-turn4"))
                .model(FixtureStreamFn.MODEL)
                .tools(E2eTask.tools(workspace))
                .build();

        // queued upfront: each drains at the injection point after a turn's tool
        // results — deterministic with scripted turns
        agent.steer("first nudge");
        agent.steer("second nudge");
        agent.prompt("create notes.md and verify it");

        // shape: steering queued while idle drains into the FIRST turn's context:
        // prompt, nudge1, [a1, tr], nudge2, [a2, tr], [a3, tr], a4(STOP)
        List<Message> transcript = agent.messages();
        assertEquals(10, transcript.size());
        assertEquals("first nudge", text((UserMessage) transcript.get(1)));
        assertEquals("second nudge", text((UserMessage) transcript.get(4)));
        AssistantMessage last = assertInstanceOf(AssistantMessage.class, transcript.get(9));
        assertEquals(StopReason.STOP, last.stopReason());
        // the queued nudges reached the model as user turns alongside the prompt
        assertEquals(3, transcript.stream().filter(UserMessage.class::isInstance).count());
    }

    @Test
    void truncatedToolCallFailsAsDataAndLoopContinues(@TempDir Path workspace) {
        ScriptedProvider provider = ScriptedProvider.builder()
                .truncatedToolCall("call_trunc", "write", Map.of("path", "notes.md"))
                .text("recovering")
                .build();
        AgentLoop loop = new AgentLoop(provider, new AgentLoopConfig.Builder()
                .toolExecution(AgentLoopConfig.ToolExecution.PARALLEL)
                .build());

        AgentLoop.LoopResult result = loop.run(FixtureStreamFn.MODEL,
                new dev.jpi.agent.AgentContext(null, List.of(), E2eTask.tools(workspace)),
                List.of(UserMessage.of("write notes.md")));

        assertEquals(StopReason.STOP, result.stopReason());
        // the truncated batch never ran: an error tool result stands in its place,
        // and the run still carried on to the closing text turn
        ToolResultMessage toolResult = result.messages().stream()
                .filter(ToolResultMessage.class::isInstance)
                .map(ToolResultMessage.class::cast)
                .findFirst().orElseThrow();
        assertTrue(toolResult.isError());
        assertTrue(((Content.Text) toolResult.content().get(0)).text().contains("truncated"));
        assertFalse(Files.exists(workspace.resolve("notes.md")),
                "a truncated call must not execute the tool");
    }

    @Test
    void oneSessionFileSpansMultipleRuns(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("poem.txt"), E2eTask.POEM);
        Path sessionFile = workspace.resolve("session.jsonl");
        // run 1 does a full tool round (read → result → next call), run 2 is a text stop
        StreamFn run1 = FixtureStreamFn.scripted("chat-read-poem", "agent-turn4");
        StreamFn run2 = FixtureStreamFn.scripted("agent-turn4");

        try (SessionRecorder recorder = new SessionRecorder(sessionFile)) {
            AgentLoop first = new AgentLoop(run1);
            first.run(FixtureStreamFn.MODEL,
                    new dev.jpi.agent.AgentContext(null, List.of(), E2eTask.tools(workspace)),
                    List.of(UserMessage.of("show poem.txt")),
                    recorder);
            AgentLoop second = new AgentLoop(run2);
            second.run(FixtureStreamFn.MODEL,
                    new dev.jpi.agent.AgentContext(null, List.of(), E2eTask.tools(workspace)),
                    List.of(UserMessage.of("summarize")),
                    recorder);
        }

        List<SessionRecord> records = SessionReader.read(sessionFile);
        // each run closes with its own agent_end
        assertEquals(2, records.stream().filter(r -> r.event() instanceof AgentEvent.End).count());
        // transcript() returns the FIRST End event's messages — run 1's, since it
        // is earliest in the file: prompt, a1, tool result, a2
        assertEquals(4, SessionReplayer.transcript(records).size());
        // the replay provider scripts one LLM call per recorded assistant response
        // (three across the two runs); a fresh loop over it re-runs the tool round
        // and stops at the first text response
        AgentLoop replayLoop = new AgentLoop(SessionReplayer.toProvider(records));
        AgentLoop.LoopResult replay = replayLoop.run(FixtureStreamFn.MODEL,
                new dev.jpi.agent.AgentContext(null, List.of(), E2eTask.tools(workspace)),
                List.of(UserMessage.of("replaying")));
        assertEquals(StopReason.STOP, replay.stopReason());
        assertEquals(4, replay.messages().size()); // prompt, a1, tool result, a2
    }

    private static String text(UserMessage message) {
        return ((Content.Text) message.content().get(0)).text();
    }
}
