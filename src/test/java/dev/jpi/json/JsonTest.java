package dev.jpi.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;

import dev.jpi.agent.AgentEvent;
import dev.jpi.ai.AssistantMessage;
import dev.jpi.ai.AssistantMessageEvent;
import dev.jpi.ai.Content;
import dev.jpi.ai.Message;
import dev.jpi.ai.StopReason;
import dev.jpi.ai.ToolResultMessage;
import dev.jpi.ai.Usage;
import dev.jpi.ai.UserMessage;

/**
 * The wire contract for messages and agent events: round-trip fidelity plus golden
 * files pinning the exact JSON vocabulary (pi RPC mode framing) the DataForge SSE
 * bridge will stream.
 */
class JsonTest {

    private static final long TS = 1700000000000L;

    // --- fixtures: one per wire shape, fixed timestamps for determinism -------

    static UserMessage userMessage() {
        return new UserMessage(
                List.of(new Content.Text("hello"), new Content.Image("aGVsbG8=", "image/png")),
                TS);
    }

    static AssistantMessage assistantMessage() {
        return new AssistantMessage(
                "anthropic-messages", "anthropic", "claude-sonnet-4",
                List.of(
                        new Content.Thinking("reasoning", "sig==", false),
                        new Content.Text("hi"),
                        new Content.ToolCall("call_1", "read", Map.of("path", "a.txt"))),
                new Usage(100, 20, 80, 10, 0.3, 0.06, 0.24, 0.03),
                StopReason.TOOL_USE, null, TS);
    }

    static ToolResultMessage toolResultMessage() {
        return new ToolResultMessage(
                "call_1", "read",
                List.of(new Content.Text("file body")),
                Map.of("bytes", 9), false, TS);
    }

    static List<Message> allMessages() {
        return List.of(userMessage(), assistantMessage(), toolResultMessage());
    }

    static AssistantMessage partial() {
        return new AssistantMessage("anthropic-messages", "anthropic", "claude-sonnet-4",
                List.of(new Content.Text("")), Usage.ZERO, StopReason.PENDING, null, TS);
    }

    static List<AssistantMessageEvent> allAssistantEvents() {
        AssistantMessage partial = partial();
        AssistantMessage finalMessage = assistantMessage();
        AssistantMessage errorMessage = new AssistantMessage(
                "anthropic-messages", "anthropic", "claude-sonnet-4",
                List.of(), Usage.ZERO, StopReason.ERROR, "HTTP 500", TS);
        return List.of(
                new AssistantMessageEvent.Start(partial),
                new AssistantMessageEvent.TextStart(partial),
                new AssistantMessageEvent.TextDelta("he", partial),
                new AssistantMessageEvent.TextEnd(partial),
                new AssistantMessageEvent.ThinkingStart(partial),
                new AssistantMessageEvent.ThinkingDelta("hm", partial),
                new AssistantMessageEvent.ThinkingEnd(partial),
                new AssistantMessageEvent.ToolCallStart("call_1", "read", partial),
                new AssistantMessageEvent.ToolCallDelta("call_1", "{}", partial),
                new AssistantMessageEvent.ToolCallEnd("call_1", partial),
                new AssistantMessageEvent.Done(finalMessage),
                new AssistantMessageEvent.Error(errorMessage));
    }

    static List<AgentEvent> allAgentEvents() {
        return List.of(
                new AgentEvent.Start(),
                new AgentEvent.TurnStart(0),
                new AgentEvent.MessageStart(userMessage()),
                new AgentEvent.MessageUpdate(new AssistantMessageEvent.TextDelta("he", partial())),
                new AgentEvent.MessageEnd(assistantMessage()),
                new AgentEvent.ToolExecutionStart("call_1", "read", Map.of("path", "a.txt")),
                new AgentEvent.ToolExecutionUpdate("call_1", Map.of("lines", 3)),
                new AgentEvent.ToolExecutionEnd("call_1", "read", toolResultMessage()),
                new AgentEvent.TurnEnd(assistantMessage(), List.of(toolResultMessage())),
                new AgentEvent.End(List.of(userMessage(), assistantMessage(), toolResultMessage())));
    }

    // --- round trip: object -> JSON -> object equals ---------------------------

    @Test
    void messagesRoundTripThroughJson() {
        for (Message message : allMessages()) {
            assertEquals(message, Json.read(Json.write(message), Message.class), message.getClass().getSimpleName());
        }
    }

    @Test
    void assistantEventsRoundTripThroughJson() {
        for (AssistantMessageEvent event : allAssistantEvents()) {
            assertEquals(event, Json.read(Json.write(event), AssistantMessageEvent.class),
                    event.getClass().getSimpleName());
        }
    }

    @Test
    void agentEventsRoundTripThroughJson() {
        for (AgentEvent event : allAgentEvents()) {
            assertEquals(event, Json.read(Json.write(event), AgentEvent.class),
                    event.getClass().getSimpleName());
        }
    }

    // --- golden files: the pinned wire format -----------------------------------

    @Test
    void messagesMatchGoldenWireFormat() throws Exception {
        assertMatchesGolden("messages.json", allMessages().stream()
                .map(m -> Json.readTree(Json.write(m)))
                .toList());
    }

    @Test
    void assistantEventsMatchGoldenWireFormat() throws Exception {
        assertMatchesGolden("assistant-events.json", allAssistantEvents().stream()
                .map(e -> Json.readTree(Json.write(e)))
                .toList());
    }

    @Test
    void agentEventsMatchGoldenWireFormat() throws Exception {
        assertMatchesGolden("agent-events.json", allAgentEvents().stream()
                .map(e -> Json.readTree(Json.write(e)))
                .toList());
    }

    @Test
    void goldenMessagesDeserializeToCurrentTypes() throws Exception {
        assertEquals(allMessages(), Json.readList(goldenString("messages.json"), Message.class));
    }

    @Test
    void goldenAssistantEventsDeserializeToCurrentTypes() throws Exception {
        assertEquals(allAssistantEvents(),
                Json.readList(goldenString("assistant-events.json"), AssistantMessageEvent.class));
    }

    @Test
    void goldenAgentEventsDeserializeToCurrentTypes() throws Exception {
        assertEquals(allAgentEvents(), Json.readList(goldenString("agent-events.json"), AgentEvent.class));
    }

    /** Forward compatibility: readers must ignore fields written by newer versions. */
    @Test
    void unknownFieldsAreToleratedOnRead() throws Exception {
        List<JsonNode> golden = golden("messages.json");
        for (int i = 0; i < golden.size(); i++) {
            ObjectNode padded = (ObjectNode) golden.get(i).deepCopy();
            padded.putObject("futureField").put("hint", "added by a newer jpi");
            Message parsed = Json.MAPPER.treeToValue(padded, Message.class);
            assertEquals(allMessages().get(i), parsed, "element " + i);
        }
    }

    // --- helpers -----------------------------------------------------------------

    private static void assertMatchesGolden(String name, List<JsonNode> actual) throws Exception {
        List<JsonNode> expected = golden(name);
        assertEquals(expected.size(), actual.size(), "golden size for " + name);
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(expected.get(i), actual.get(i), name + " element " + i);
        }
    }

    private static List<JsonNode> golden(String name) throws Exception {
        try (InputStream in = JsonTest.class.getResourceAsStream("/golden/" + name)) {
            assertNotNull(in, "golden file missing: src/test/resources/golden/" + name);
            JsonNode array = Json.MAPPER.readTree(in.readAllBytes());
            List<JsonNode> elements = new java.util.ArrayList<>();
            array.forEach(elements::add);
            return elements;
        }
    }

    private static String goldenString(String name) throws Exception {
        try (InputStream in = JsonTest.class.getResourceAsStream("/golden/" + name)) {
            assertNotNull(in, "golden file missing: src/test/resources/golden/" + name);
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
