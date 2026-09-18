package dev.jpi.ai.providers;

import com.fasterxml.jackson.databind.JsonNode;

import dev.jpi.ai.AssistantMessage;
import dev.jpi.ai.AssistantMessageEvent;
import dev.jpi.ai.AssistantMessageEventStream;
import dev.jpi.ai.Content;
import dev.jpi.ai.Context;
import dev.jpi.ai.ErrorClassifier;
import dev.jpi.ai.ErrorKind;
import dev.jpi.ai.Model;
import dev.jpi.ai.StopReason;
import dev.jpi.ai.StreamOptions;
import dev.jpi.ai.Tool;
import dev.jpi.ai.ToolResultMessage;
import dev.jpi.ai.Usage;
import dev.jpi.ai.UserMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Seam 4: {@link AnthropicProvider} — {@code Context → /v1/messages} request mapping
 * and canned SSE bytes → event sequence + final message. No network.
 */
class AnthropicProviderTest {

    private static final Model MODEL = new Model(
            "claude-sonnet-4-5", "anthropic-messages", "anthropic",
            "https://api.anthropic.com", 200_000, 4096);

    @Test
    void requestCarriesSystemMessagesToolsAndToolResults() throws Exception {
        Context context = new Context(
                "be nice",
                List.of(
                        new UserMessage(List.of(
                                new Content.Text("hi"),
                                new Content.Image("AAAA", "image/png")), 1),
                        new AssistantMessage(MODEL.api(), MODEL.provider(), MODEL.id(),
                                List.of(
                                        new Content.Text("checking"),
                                        new Content.ToolCall("toolu_1", "get_weather", Map.of("city", "SF"))),
                                Usage.ZERO, StopReason.TOOL_USE, null, null, 2),
                        new ToolResultMessage("toolu_1", "get_weather",
                                List.of(new Content.Text("sunny")), Map.of(), false, 3)),
                List.of(new Tool("get_weather", "get it", Map.of("type", "object"))));

        Map<String, Object> request = AnthropicProvider.buildRequest(MODEL, context);

        // default (prompt caching ON): system becomes a block array, and exactly
        // three breakpoints are marked — system, the last tool, the last block of
        // the last user message. Earlier blocks stay unmarked.
        String expected = """
                {
                  "model": "claude-sonnet-4-5",
                  "max_tokens": 4096,
                  "stream": true,
                  "system": [{"type": "text", "text": "be nice", "cache_control": {"type": "ephemeral"}}],
                  "messages": [
                    {"role": "user", "content": [
                      {"type": "text", "text": "hi"},
                      {"type": "image", "source": {"type": "base64", "media_type": "image/png", "data": "AAAA"}}
                    ]},
                    {"role": "assistant", "content": [
                      {"type": "text", "text": "checking"},
                      {"type": "tool_use", "id": "toolu_1", "name": "get_weather", "input": {"city": "SF"}}
                    ]},
                    {"role": "user", "content": [
                      {"type": "tool_result", "tool_use_id": "toolu_1", "content": [{"type": "text", "text": "sunny"}], "cache_control": {"type": "ephemeral"}}
                    ]}
                  ],
                  "tools": [
                    {"name": "get_weather", "description": "get it", "input_schema": {"type": "object"}, "cache_control": {"type": "ephemeral"}}
                  ]
                }
                """;
        JsonNode expectedTree = Json.MAPPER.readTree(expected);
        JsonNode actualTree = Json.MAPPER.readTree(Json.write(request));
        assertEquals(expectedTree, actualTree);
    }

    @Test
    void requestMarksOnlyTheLastToolAndLastUserBlockForCaching() throws Exception {
        Context context = new Context(
                null,
                List.of(
                        new UserMessage(List.of(new Content.Text("hi")), 1),
                        new UserMessage(List.of(new Content.Text("again")), 2)),
                List.of(new Tool("get_weather", "get it", Map.of("type", "object")),
                        new Tool("read", "read it", Map.of("type", "object"))));

        Map<String, Object> request = AnthropicProvider.buildRequest(MODEL, context);

        String expected = """
                {
                  "model": "claude-sonnet-4-5",
                  "max_tokens": 4096,
                  "stream": true,
                  "messages": [
                    {"role": "user", "content": [{"type": "text", "text": "hi"}]},
                    {"role": "user", "content": [{"type": "text", "text": "again", "cache_control": {"type": "ephemeral"}}]}
                  ],
                  "tools": [
                    {"name": "get_weather", "description": "get it", "input_schema": {"type": "object"}},
                    {"name": "read", "description": "read it", "input_schema": {"type": "object"}, "cache_control": {"type": "ephemeral"}}
                  ]
                }
                """;
        JsonNode expectedTree = Json.MAPPER.readTree(expected);
        JsonNode actualTree = Json.MAPPER.readTree(Json.write(request));
        assertEquals(expectedTree, actualTree);
    }

    @Test
    void promptCachingOffLeavesTheRequestUnmarked() throws Exception {
        Context context = new Context(
                "be nice",
                List.of(new UserMessage(List.of(new Content.Text("hi")), 1)),
                List.of(new Tool("get_weather", "get it", Map.of("type", "object"))));

        Map<String, Object> request = AnthropicProvider.buildRequest(MODEL, context, false);

        String expected = """
                {
                  "model": "claude-sonnet-4-5",
                  "max_tokens": 4096,
                  "stream": true,
                  "system": "be nice",
                  "messages": [
                    {"role": "user", "content": [{"type": "text", "text": "hi"}]}
                  ],
                  "tools": [
                    {"name": "get_weather", "description": "get it", "input_schema": {"type": "object"}}
                  ]
                }
                """;
        JsonNode expectedTree = Json.MAPPER.readTree(expected);
        JsonNode actualTree = Json.MAPPER.readTree(Json.write(request));
        assertEquals(expectedTree, actualTree);
    }

    @Test
    void sseEventsMapToProtocolAndAssembleToolArguments() {
        String sse = """
                event: message_start
                data: {"type":"message_start","message":{"id":"msg_1","usage":{"input_tokens":25,"output_tokens":1}}}

                event: content_block_start
                data: {"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"let me think"}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"sig=="}}

                event: content_block_stop
                data: {"type":"content_block_stop","index":0}

                event: content_block_start
                data: {"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"Hello"}}

                event: content_block_stop
                data: {"type":"content_block_stop","index":1}

                event: content_block_start
                data: {"type":"content_block_start","index":2,"content_block":{"type":"tool_use","id":"toolu_1","name":"get_weather"}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"{\\"city\\": "}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"\\"SF\\"}"}}

                event: content_block_stop
                data: {"type":"content_block_stop","index":2}

                event: message_delta
                data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":50}}

                event: message_stop
                data: {"type":"message_stop"}
                """;
        AssistantMessageEventStream out = feed(MODEL, sse);

        List<String> labels = new ArrayList<>();
        for (AssistantMessageEvent event : out) {
            labels.add(label(event));
        }
        assertEquals(List.of(
                "start",
                "thinking_start", "thinking_delta", "thinking_end",
                "text_start", "text_delta", "text_end",
                "toolcall_start", "toolcall_delta", "toolcall_delta", "toolcall_end",
                "done"), labels);

        AssistantMessage message = out.result().join();
        assertEquals(StopReason.TOOL_USE, message.stopReason());
        assertEquals(
                List.of(
                        new Content.Thinking("let me think", "sig==", false),
                        new Content.Text("Hello"),
                        new Content.ToolCall("toolu_1", "get_weather", Map.of("city", "SF"))),
                message.content());
        assertEquals(new Usage(25, 50, 0, 0, 0, 0, 0, 0), message.usage());
    }

    @Test
    void sseRawControlCharactersInToolArgumentsAreSalvaged() {
        // partial_json carries an escaped newline; once the SSE envelope is decoded
        // the accumulated arguments contain a RAW newline inside a string literal.
        AssistantMessageEventStream out = feed(MODEL, """
                event: message_start
                data: {"type":"message_start","message":{"id":"msg_1"}}

                event: content_block_start
                data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_1","name":"run"}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\\"cmd\\": \\"ls\\n--all\\"}"}}

                event: content_block_stop
                data: {"type":"content_block_stop","index":0}

                event: message_delta
                data: {"type":"message_delta","delta":{"stop_reason":"tool_use"}}

                event: message_stop
                data: {"type":"message_stop"}
                """);

        AssistantMessage message = out.result().join();
        assertEquals(StopReason.TOOL_USE, message.stopReason(), "salvage must not fail the whole message");
        assertEquals(List.of(new Content.ToolCall("toolu_1", "run", Map.of("cmd", "ls\n--all"))), message.content());
    }

    @Test
    void sseUnparseableToolArgumentsBecomeEmptyWithoutFailingTheMessage() {
        AssistantMessageEventStream out = feed(MODEL, """
                event: message_start
                data: {"type":"message_start","message":{"id":"msg_1"}}

                event: content_block_start
                data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_1","name":"run"}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"<<<garbage>>>"}}

                event: content_block_stop
                data: {"type":"content_block_stop","index":0}

                event: message_delta
                data: {"type":"message_delta","delta":{"stop_reason":"tool_use"}}

                event: message_stop
                data: {"type":"message_stop"}
                """);

        AssistantMessage message = out.result().join();
        // the tool call fails (empty args → schema validation error tool result),
        // not the message — the loop continues and the model can re-issue.
        assertEquals(StopReason.TOOL_USE, message.stopReason());
        assertEquals(List.of(new Content.ToolCall("toolu_1", "run", Map.of())), message.content());
    }

    @Test
    void sseErrorEventBecomesTerminalErrorData() {
        AssistantMessageEventStream out = feed(MODEL, """
                event: error
                data: {"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}
                """);

        List<String> labels = new ArrayList<>();
        out.forEach(e -> labels.add(label(e)));
        assertEquals(List.of("error"), labels);
        AssistantMessage message = out.result().join();
        assertEquals(StopReason.ERROR, message.stopReason());
        assertEquals("Overloaded", message.errorMessage());
    }

    @Test
    void truncatedSseYieldsErrorResult() {
        AssistantMessageEventStream out = feed(MODEL, """
                event: message_start
                data: {"type":"message_start","message":{"id":"msg_1"}}
                """);

        out.forEach(e -> { });
        AssistantMessage message = out.result().join();
        assertTrue(message.stopReason() == StopReason.ERROR, "truncated stream must end in an error, not hang");
    }

    @Test
    void httpFailureCarriesMessageAndClassification() {
        AssistantMessage rateLimited = AnthropicProvider.failure(MODEL, 429, "rate limit exceeded");
        assertEquals(StopReason.ERROR, rateLimited.stopReason());
        assertEquals(ErrorKind.RATE_LIMIT, rateLimited.diagnostics());
        assertEquals("HTTP 429: rate limit exceeded", rateLimited.errorMessage());

        AssistantMessage overflow = AnthropicProvider.failure(MODEL, 400,
                "invalid_request_error: prompt is too long: 300000 tokens > 200000 maximum");
        assertEquals(ErrorKind.CONTEXT_OVERFLOW, overflow.diagnostics());

        AssistantMessage invalid = AnthropicProvider.failure(MODEL, 400, "invalid: messages: empty");
        assertEquals(ErrorKind.UNKNOWN, invalid.diagnostics());
    }

    @Test
    void transportFailureCarriesNetworkClassification() {
        AssistantMessage failed = AnthropicProvider.failure(MODEL, "request failed: connection reset",
                ErrorClassifier.classify(new java.io.IOException("connection reset")));
        assertEquals(StopReason.ERROR, failed.stopReason());
        assertEquals(ErrorKind.NETWORK, failed.diagnostics());
        assertEquals("request failed: connection reset", failed.errorMessage());
    }

    /** Feeds canned SSE bytes (event name/data pairs) into an {@link AnthropicStreamParser}. */
    static AssistantMessageEventStream feed(Model model, String sse) {
        AssistantMessageEventStream out = new AssistantMessageEventStream();
        AnthropicStreamParser parser = new AnthropicStreamParser(model, out);
        SseParser sseParser = new SseParser(parser::sseEvent);
        for (String line : sse.split("\n", -1)) {
            sseParser.processLine(line);
        }
        sseParser.end();
        parser.complete();
        return out;
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
