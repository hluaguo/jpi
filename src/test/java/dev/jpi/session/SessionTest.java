package dev.jpi.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jpi.Fixtures;
import dev.jpi.agent.AgentContext;
import dev.jpi.agent.AgentEvent;
import dev.jpi.agent.AgentLoop;
import dev.jpi.agent.AgentTool;
import dev.jpi.agent.AgentToolResult;
import dev.jpi.json.Json;
import dev.jpi.ai.Content;
import dev.jpi.ai.Message;
import dev.jpi.ai.providers.ScriptedProvider;
import dev.jpi.ai.UserMessage;
import dev.jpi.util.CancellationToken;

/**
 * Crash-safe JSONL session persistence: record → read round-trips exactly, a torn
 * final line (crash during write) is tolerated, and recorded assistant responses
 * replay through {@link ScriptedProvider} into an identical run — the demo-day
 * fallback.
 */
class SessionTest {

    @TempDir
    Path dir;

    // --- the deterministic run used by every test --------------------------------

    private static AgentTool echoTool() {
        return new AgentTool() {
            @Override
            public String name() {
                return "echo";
            }

            @Override
            public String description() {
                return "echoes its arguments";
            }

            @Override
            public Map<String, Object> parameters() {
                return Map.of("type", "object");
            }

            @Override
            public AgentToolResult execute(String toolCallId, Map<String, Object> args,
                                           CancellationToken signal,
                                           java.util.function.Consumer<Map<String, Object>> onUpdate) {
                return AgentToolResult.text("echo: " + args);
            }
        };
    }

    private static ScriptedProvider provider() {
        return ScriptedProvider.builder()
                .toolCall("call_1", "echo", Map.of("text", "hi"))
                .text("all done")
                .build();
    }

    private static void run(ScriptedProvider provider, java.util.function.Consumer<AgentEvent> listener) {
        new AgentLoop(provider).run(
                Fixtures.model(),
                new AgentContext(null, List.of(), List.of(echoTool())),
                List.of(UserMessage.of("go")), listener);
    }

    private List<AgentEvent> recordRun(String fileName) throws IOException {
        List<AgentEvent> events = new ArrayList<>();
        try (SessionRecorder recorder = new SessionRecorder(dir.resolve(fileName))) {
            run(provider(), events::add);
            for (AgentEvent event : events) {
                recorder.accept(event);
            }
        }
        return events;
    }

    // --- record -> read round trip ------------------------------------------------

    @Test
    void recordThenReadRoundTripsExactly() throws IOException {
        List<AgentEvent> recorded = recordRun("run.jsonl");

        List<SessionRecord> read = SessionReader.read(dir.resolve("run.jsonl"));

        assertEquals(recorded.size(), read.size());
        for (int i = 0; i < recorded.size(); i++) {
            assertEquals(recorded.get(i), read.get(i).event(), "event " + i);
        }
    }

    @Test
    void everyLineIsVersionedEnvelopeWithTimestamp() throws IOException {
        recordRun("run.jsonl");

        List<String> lines = Files.readAllLines(dir.resolve("run.jsonl"), StandardCharsets.UTF_8);
        assertTrue(lines.size() > 5);
        for (String line : lines) {
            ObjectNode parsed = (ObjectNode) Json.MAPPER.readTree(line);
            assertEquals(1, parsed.get("v").asInt());
            assertTrue(parsed.get("ts").asLong() > 0);
            assertTrue(parsed.get("event").isObject());
        }
    }

    @Test
    void timestampsAreMonotonicAndPresent() throws IOException {
        recordRun("run.jsonl");
        List<SessionRecord> read = SessionReader.read(dir.resolve("run.jsonl"));
        for (int i = 1; i < read.size(); i++) {
            assertTrue(read.get(i).ts() >= read.get(i - 1).ts());
        }
    }

    // --- crash safety ---------------------------------------------------------------

    /** A crash mid-write tears the final line; everything before it must survive. */
    @Test
    void tornFinalLineIsTolerated() throws IOException {
        List<AgentEvent> recorded = recordRun("run.jsonl");
        List<String> lines = Files.readAllLines(dir.resolve("run.jsonl"), StandardCharsets.UTF_8);

        String torn = lines.get(lines.size() - 2).substring(0, 20) + lines.get(lines.size() - 1).substring(0, 15);
        List<String> truncated = new ArrayList<>(lines.subList(0, lines.size() - 2));
        truncated.add(torn);
        Files.write(dir.resolve("run.jsonl"), String.join("\n", truncated).getBytes(StandardCharsets.UTF_8));

        List<SessionRecord> read = SessionReader.read(dir.resolve("run.jsonl"));
        assertEquals(recorded.size() - 2, read.size());
        for (int i = 0; i < read.size(); i++) {
            assertEquals(recorded.get(i), read.get(i).event());
        }
    }

    @Test
    void corruptMiddleLineThrows() throws IOException {
        recordRun("run.jsonl");
        List<String> lines = Files.readAllLines(dir.resolve("run.jsonl"), StandardCharsets.UTF_8);
        lines.set(1, "{\"v\":1,\"ts\":1,\"event\":{\"type\":");
        Files.write(dir.resolve("run.jsonl"), String.join("\n", lines).getBytes(StandardCharsets.UTF_8));

        assertThrows(IOException.class, () -> SessionReader.read(dir.resolve("run.jsonl")));
    }

    @Test
    void structurallyInvalidLinesAreCorruptNotACrash() throws IOException {
        // valid JSON without the ts/event envelope: same corrupt-line contract as
        // unparsable JSON, never an NPE (pi's reader skips junk lines; jpi's stricter
        // contract still distinguishes a torn final line)
        recordRun("run.jsonl");
        List<String> lines = Files.readAllLines(dir.resolve("run.jsonl"), StandardCharsets.UTF_8);
        lines.add(lines.size() - 1, "{\"v\":1}");
        Files.write(dir.resolve("run.jsonl"), String.join("\n", lines).getBytes(StandardCharsets.UTF_8));

        assertThrows(IOException.class, () -> SessionReader.read(dir.resolve("run.jsonl")));

        // as a torn final line it is tolerated like any other truncated write
        List<String> tailOnly = new ArrayList<>();
        tailOnly.add("{}");
        Files.write(dir.resolve("torn.jsonl"), String.join("\n", tailOnly).getBytes(StandardCharsets.UTF_8));
        assertEquals(List.of(), SessionReader.read(dir.resolve("torn.jsonl")));
    }

    // --- listing ---------------------------------------------------------------------

    @Test
    void listSessionsFindsJsonlFilesSorted() throws IOException {
        recordRun("b.jsonl");
        recordRun("a.jsonl");
        Files.writeString(dir.resolve("notes.txt"), "not a session");

        List<String> names = SessionReader.listSessions(dir).stream()
                .map(Path::getFileName).map(Path::toString).toList();

        assertEquals(List.of("a.jsonl", "b.jsonl"), names);
    }

    // --- replay -----------------------------------------------------------------------

    @Test
    void replayerFeedsEventsVerbatim() throws IOException {
        List<AgentEvent> recorded = recordRun("run.jsonl");
        List<SessionRecord> read = SessionReader.read(dir.resolve("run.jsonl"));

        List<AgentEvent> replayed = new ArrayList<>();
        SessionReplayer.replay(read, replayed::add);

        assertEquals(recorded, replayed);
    }

    @Test
    void replayerRebuildsTranscript() throws IOException {
        recordRun("run.jsonl");
        List<SessionRecord> read = SessionReader.read(dir.resolve("run.jsonl"));

        List<Message> transcript = SessionReplayer.transcript(read);

        // user prompt + assistant + toolResult + final assistant
        assertEquals(4, transcript.size());
        assertEquals("user", roleOf(transcript.get(0)));
        assertEquals("assistant", roleOf(transcript.get(1)));
        assertEquals("toolResult", roleOf(transcript.get(2)));
        assertEquals("assistant", roleOf(transcript.get(3)));
    }

    /** The demo-day fallback: a recorded session becomes a runnable provider. */
    @Test
    void recordedResponsesReplayThroughScriptedProviderIdentically() throws IOException {
        List<AgentEvent> original = recordRun("run.jsonl");
        List<SessionRecord> read = SessionReader.read(dir.resolve("run.jsonl"));

        ScriptedProvider replayProvider = SessionReplayer.toProvider(read);
        List<AgentEvent> replayed = new ArrayList<>();
        run(replayProvider, replayed::add);

        assertEquals(scrub(original), scrub(replayed));
    }

    // --- helpers -----------------------------------------------------------------------

    private static String roleOf(Message message) {
        JsonNode tree = Json.MAPPER.valueToTree(message);
        return tree.get("type").asText();
    }

    /** Strips wall-clock timestamps so two runs compare by structure, not by when they ran. */
    private static List<JsonNode> scrub(List<AgentEvent> events) {
        return events.stream().map(e -> scrub(Json.MAPPER.valueToTree(e))).toList();
    }

    private static JsonNode scrub(JsonNode node) {
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            object.remove("timestamp");
            object.forEach(SessionTest::scrub);
        } else if (node.isArray()) {
            node.forEach(SessionTest::scrub);
        }
        return node;
    }
}
