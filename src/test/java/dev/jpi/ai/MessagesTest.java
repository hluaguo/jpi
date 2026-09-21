package dev.jpi.ai;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Message records take snapshots of caller-owned maps, matching pi's wire objects
 * (fresh per parse — a later caller mutation can never reshape a recorded message).
 * AgentToolResult.details already copies; these records must too.
 */
class MessagesTest {

    @Test
    void toolResultDetailsAreCopiedNotViewed() {
        Map<String, Object> mutable = new HashMap<>();
        mutable.put("exitCode", 0);
        ToolResultMessage message = new ToolResultMessage(
                "call_1", "bash", List.of(new Content.Text("out")), mutable, false, 1);

        mutable.put("later", "mutated");

        assertFalse(message.details().containsKey("later"), "record must snapshot the map");
        assertThrows(UnsupportedOperationException.class, () -> message.details().put("x", 1));
    }

    @Test
    void jsonNullArgumentValuesAreTolerated() {
        Map<String, Object> withNull = new HashMap<>();
        withNull.put("limit", null);
        Content.ToolCall call = new Content.ToolCall("call_1", "read", withNull);
        assertEquals(null, call.arguments().get("limit"), "JSON null is a legal argument value");
    }

    @Test
    void toolCallArgumentsAreCopiedNotViewed() {
        Map<String, Object> mutable = new HashMap<>();
        mutable.put("path", "a.txt");
        Content.ToolCall call = new Content.ToolCall("call_1", "read", mutable);

        mutable.put("later", "mutated");

        assertFalse(call.arguments().containsKey("later"), "record must snapshot the map");
        assertEquals("a.txt", call.arguments().get("path"));
        assertThrows(UnsupportedOperationException.class, () -> call.arguments().put("x", 1));
    }
}
