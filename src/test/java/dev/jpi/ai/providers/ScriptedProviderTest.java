package dev.jpi.ai.providers;

import dev.jpi.ai.AssistantMessage;
import dev.jpi.ai.AssistantMessageEvent;
import dev.jpi.ai.AssistantMessageEventStream;
import dev.jpi.ai.Content;
import dev.jpi.ai.Context;
import dev.jpi.ai.Model;
import dev.jpi.ai.StopReason;
import dev.jpi.ai.StreamOptions;
import dev.jpi.ai.UserMessage;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Seam 2 (part 1): {@link ScriptedProvider} materializes pi's streaming protocol
 * (start; text/thinking/toolcall start|delta|end; done; error) from canned scripts.
 */
class ScriptedProviderTest {

    private static final Model MODEL =
            new Model("test-model", "scripted", "scripted", "http://scripted.invalid", 8192, 4096);

    @Test
    void textScriptEmitsTextEventSequenceThenDone() {
        ScriptedProvider provider = ScriptedProvider.builder().text("Hello").build();

        AssistantMessageEventStream stream =
                provider.stream(MODEL, new Context(null, List.of(), List.of()), StreamOptions.none());

        List<String> types = new ArrayList<>();
        AssistantMessageEvent textDelta = null;
        for (AssistantMessageEvent event : stream) {
            types.add(label(event));
            if (event instanceof AssistantMessageEvent.TextDelta d) {
                textDelta = d;
            }
        }
        assertEquals(List.of("start", "text_start", "text_delta", "text_end", "done"), types);

        // the delta's partial carries the accumulated text so far
        assertEquals(List.of(new Content.Text("Hello")), textDelta.partial().content());

        AssistantMessage message = stream.result().join();
        assertEquals(StopReason.STOP, message.stopReason());
        assertEquals(List.of(new Content.Text("Hello")), message.content());
    }

    @Test
    void toolCallScriptEmitsToolcallEventsAndAssemblesArguments() {
        ScriptedProvider provider = ScriptedProvider.builder()
                .toolCall("call_1", "read", Map.of("path", "a.txt"))
                .build();

        AssistantMessageEventStream stream =
                provider.stream(MODEL, new Context(null, List.of(), List.of()), StreamOptions.none());

        List<String> types = new ArrayList<>();
        AssistantMessageEvent.ToolCallDelta delta = null;
        for (AssistantMessageEvent event : stream) {
            types.add(label(event));
            if (event instanceof AssistantMessageEvent.ToolCallDelta d) {
                delta = d;
            }
        }
        assertEquals(List.of("start", "toolcall_start", "toolcall_delta", "toolcall_end", "done"), types);
        assertEquals("call_1", delta.id());
        assertEquals("{\"path\":\"a.txt\"}", delta.delta().replace(" ", ""));

        AssistantMessage message = stream.result().join();
        assertEquals(StopReason.TOOL_USE, message.stopReason());
        assertEquals(List.of(new Content.ToolCall("call_1", "read", Map.of("path", "a.txt"))), message.content());
    }

    @Test
    void errorAndAbortedScriptsEndInTerminalErrorEvents() {
        ScriptedProvider provider = ScriptedProvider.builder()
                .error("overloaded")
                .aborted()
                .build();

        AssistantMessageEventStream errorStream =
                provider.stream(MODEL, new Context(null, List.of(), List.of()), StreamOptions.none());
        List<String> errorTypes = new ArrayList<>();
        errorStream.forEach(e -> errorTypes.add(label(e)));
        assertEquals(List.of("error"), errorTypes);
        AssistantMessage errorMessage = errorStream.result().join();
        assertEquals(StopReason.ERROR, errorMessage.stopReason());
        assertEquals("overloaded", errorMessage.errorMessage());

        AssistantMessageEventStream abortStream =
                provider.stream(MODEL, new Context(null, List.of(), List.of()), StreamOptions.none());
        abortStream.forEach(e -> { });
        AssistantMessage abortMessage = abortStream.result().join();
        assertEquals(StopReason.ABORTED, abortMessage.stopReason());
    }

    @Test
    void successiveCallsPopScriptsInOrderAndRecordCalls() {
        ScriptedProvider provider = ScriptedProvider.builder()
                .text("first")
                .text("second")
                .build();
        Context context = new Context("sys", List.of(UserMessage.of("hi")), List.of());

        AssistantMessageEventStream first = provider.stream(MODEL, context, StreamOptions.none());
        first.forEach(e -> { });
        assertEquals("first", first.result().join().content().get(0) instanceof Content.Text t ? t.text() : "?");

        AssistantMessageEventStream second = provider.stream(MODEL, context, StreamOptions.none());
        second.forEach(e -> { });
        assertEquals("second", second.result().join().content().get(0) instanceof Content.Text t ? t.text() : "?");

        assertThrows(IllegalStateException.class, () -> provider.stream(MODEL, context, StreamOptions.none()));

        assertEquals(2, provider.calls().size());
        assertEquals(MODEL, provider.calls().get(0).model());
        assertEquals(context, provider.calls().get(0).context());
    }

    private static String label(AssistantMessageEvent event) {
        if (event instanceof AssistantMessageEvent.Start) return "start";
        if (event instanceof AssistantMessageEvent.TextStart) return "text_start";
        if (event instanceof AssistantMessageEvent.TextDelta) return "text_delta";
        if (event instanceof AssistantMessageEvent.TextEnd) return "text_end";
        if (event instanceof AssistantMessageEvent.ThinkingStart) return "thinking_start";
        if (event instanceof AssistantMessageEvent.ThinkingDelta) return "thinking_delta";
        if (event instanceof AssistantMessageEvent.ThinkingEnd) return "thinking_end";
        if (event instanceof AssistantMessageEvent.ToolCallStart) return "toolcall_start";
        if (event instanceof AssistantMessageEvent.ToolCallDelta) return "toolcall_delta";
        if (event instanceof AssistantMessageEvent.ToolCallEnd) return "toolcall_end";
        if (event instanceof AssistantMessageEvent.Done) return "done";
        if (event instanceof AssistantMessageEvent.Error) return "error";
        throw new AssertionError("unknown event: " + event);
    }
}
