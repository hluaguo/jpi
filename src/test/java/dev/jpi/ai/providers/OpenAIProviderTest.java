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
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Seam 5: {@link OpenAICompletionsProvider} — {@code Context → /v1/chat/completions}
 * request mapping, SSE chunk parsing, index-based tool_calls delta assembly,
 * finish_reason → StopReason, usage. No network.
 */
class OpenAIProviderTest {

    private static final Model MODEL = new Model(
            "gpt-4o", "openai-completions", "openai",
            "https://api.openai.com", 128_000, 4096);

    @Test
    void requestCarriesSystemMessagesToolCallsAndTools() throws Exception {
        Context context = new Context(
                "be nice",
                List.of(
                        new UserMessage(List.of(
                                new Content.Text("hi"),
                                new Content.Image("AAAA", "image/png")), 1),
                        new UserMessage(List.of(new Content.Text("plain")), 2),
                        new AssistantMessage(MODEL.api(), MODEL.provider(), MODEL.id(),
                                List.of(
                                        new Content.Text("checking"),
                                        new Content.ToolCall("call_1", "get_weather", Map.of("city", "SF"))),
                                Usage.ZERO, StopReason.TOOL_USE, null, null, 3),
                        new ToolResultMessage("call_1", "get_weather",
                                List.of(new Content.Text("sunny")), Map.of(), false, 4)),
                List.of(new Tool("get_weather", "get it", Map.of("type", "object"))));

        Map<String, Object> request = OpenAICompletionsProvider.buildRequest(MODEL, context);

        String expected = """
                {
                  "model": "gpt-4o",
                  "stream": true,
                  "stream_options": {"include_usage": true},
                  "messages": [
                    {"role": "system", "content": "be nice"},
                    {"role": "user", "content": [
                      {"type": "text", "text": "hi"},
                      {"type": "image_url", "image_url": {"url": "data:image/png;base64,AAAA"}}
                    ]},
                    {"role": "user", "content": "plain"},
                    {"role": "assistant", "content": "checking", "tool_calls": [
                      {"id": "call_1", "type": "function",
                       "function": {"name": "get_weather", "arguments": "{\\"city\\":\\"SF\\"}"}}
                    ]},
                    {"role": "tool", "tool_call_id": "call_1", "content": "sunny"}
                  ],
                  "tools": [
                    {"type": "function", "function": {"name": "get_weather", "description": "get it", "parameters": {"type": "object"}}}
                  ]
                }
                """;
        JsonNode expectedTree = Json.MAPPER.readTree(expected);
        JsonNode actualTree = Json.MAPPER.readTree(Json.write(request));
        assertEquals(expectedTree, actualTree);
    }

    @Test
    void sseChunksAssembleToolCallsByIndexAndCarryUsage() {
        AssistantMessageEventStream out = feed(MODEL, """
                data: {"choices":[{"index":0,"delta":{"role":"assistant","content":"Hel"},"finish_reason":null}]}

                data: {"choices":[{"index":0,"delta":{"content":"lo"},"finish_reason":null}]}

                data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"get_weather","arguments":""}}]},"finish_reason":null}]}

                data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\\"city\\": "}}]},"finish_reason":null}]}

                data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":1,"id":"call_2","type":"function","function":{"name":"get_time","arguments":""}}]},"finish_reason":null}]}

                data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":1,"function":{"arguments":"{\\"tz\\":\\"UTC\\"}"}}]},"finish_reason":null}]}

                data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\\"SF\\"}"}}]},"finish_reason":null}]}

                data: {"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}

                data: {"id":"chatcmpl-1","choices":[],"usage":{"prompt_tokens":25,"completion_tokens":50}}

                data: [DONE]
                """);

        List<String> labels = new ArrayList<>();
        for (AssistantMessageEvent event : out) {
            labels.add(label(event));
        }
        assertEquals(List.of(
                "start",
                "text_start", "text_delta", "text_delta", "text_end",
                "toolcall_start", "toolcall_delta",
                "toolcall_start", "toolcall_delta",
                "toolcall_delta",
                "toolcall_end", "toolcall_end",
                "done"), labels);

        AssistantMessage message = out.result().join();
        assertEquals(StopReason.TOOL_USE, message.stopReason());
        assertEquals(
                List.of(
                        new Content.Text("Hello"),
                        new Content.ToolCall("call_1", "get_weather", Map.of("city", "SF")),
                        new Content.ToolCall("call_2", "get_time", Map.of("tz", "UTC"))),
                message.content());
        assertEquals(new Usage(25, 50, 0, 0, 0, 0, 0, 0), message.usage());
    }

    @Test
    void sseRawControlCharactersInToolArgumentsAreSalvaged() {
        // the arguments field value contains a RAW newline inside a string literal
        // once the SSE envelope is decoded; salvage must not fail the message.
        AssistantMessageEventStream out = feed(MODEL, """
                data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"run","arguments":""}}]},"finish_reason":null}]}

                data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\\\"cmd\\\": \\\"ls\\n--all\\\"}"}}]},"finish_reason":null}]}

                data: {"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}

                data: [DONE]
                """);

        AssistantMessage message = out.result().join();
        assertEquals(StopReason.TOOL_USE, message.stopReason());
        assertEquals(List.of(new Content.ToolCall("call_1", "run", Map.of("cmd", "ls\n--all"))), message.content());
    }

    @Test
    void plainStopFinishYieldsStopReasonStop() {
        AssistantMessageEventStream out = feed(MODEL, """
                data: {"choices":[{"index":0,"delta":{"role":"assistant","content":"hi"},"finish_reason":null}]}

                data: {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                data: [DONE]
                """);

        List<String> labels = new ArrayList<>();
        out.forEach(e -> labels.add(label(e)));
        assertEquals(List.of("start", "text_start", "text_delta", "text_end", "done"), labels);
        assertEquals(StopReason.STOP, out.result().join().stopReason());
    }

    @Test
    void truncatedStreamWithoutDoneYieldsErrorResult() {
        AssistantMessageEventStream out = feed(MODEL, """
                data: {"choices":[{"index":0,"delta":{"role":"assistant","content":"hi"},"finish_reason":null}]}
                """);

        out.forEach(e -> { });
        assertTrue(out.result().join().stopReason() == StopReason.ERROR,
                "stream cut before [DONE] must end in an error, not hang");
    }

    @Test
    void httpFailureCarriesMessageAndClassification() {
        AssistantMessage rateLimited = OpenAICompletionsProvider.failure(MODEL, 429, "rate limit exceeded");
        assertEquals(StopReason.ERROR, rateLimited.stopReason());
        assertEquals(ErrorKind.RATE_LIMIT, rateLimited.diagnostics());
        assertEquals("HTTP 429: rate limit exceeded", rateLimited.errorMessage());

        AssistantMessage overflow = OpenAICompletionsProvider.failure(MODEL, 400,
            "This model's maximum context length is 8192 tokens");
        assertEquals(ErrorKind.CONTEXT_OVERFLOW, overflow.diagnostics());

        AssistantMessage invalid = OpenAICompletionsProvider.failure(MODEL, 400, "invalid body");
        assertEquals(ErrorKind.UNKNOWN, invalid.diagnostics());
    }

    @Test
    void transportFailureCarriesNetworkClassification() {
        AssistantMessage failed = OpenAICompletionsProvider.failure(MODEL, "request failed: timed out",
            ErrorClassifier.classify(new java.net.http.HttpTimeoutException("timed out")));
        assertEquals(StopReason.ERROR, failed.stopReason());
        assertEquals(ErrorKind.NETWORK, failed.diagnostics());
        assertEquals("request failed: timed out", failed.errorMessage());
    }

    @Test
    void pumpObservesCancellationBetweenLines() {
        dev.jpi.util.CancellationToken cancel = new dev.jpi.util.CancellationToken();
        List<String> lines = List.of("data: {\"choices\":[{\"delta\":{\"content\":\"a\"}}]}", "",
                "data: {\"choices\":[{\"delta\":{\"content\":\"b\"}}]}", "");
        AtomicInteger nextCalls = new AtomicInteger();
        AtomicInteger processed = new AtomicInteger();
        Iterator<String> raw = lines.iterator();
        Iterator<String> aborting = new Iterator<>() {
            @Override
            public boolean hasNext() {
                return raw.hasNext();
            }

            @Override
            public String next() {
                if (nextCalls.getAndIncrement() >= 1) {
                    cancel.abort(); // fires between lines, as an abort mid-generation would
                }
                return raw.next();
            }
        };
        assertThrows(RequestAbortedException.class,
                () -> OpenAICompletionsProvider.pumpSse(aborting, l -> processed.incrementAndGet(), cancel));
        assertTrue(processed.get() >= 1, "lines before the abort were processed");
    }

    @Test
    void abortedRequestBecomesAbortedDataNotError() {
        AssistantMessage aborted = OpenAICompletionsProvider.abortedFailure(MODEL);
        assertEquals(StopReason.ABORTED, aborted.stopReason());
        assertEquals("Request was aborted", aborted.errorMessage());
    }

    static AssistantMessageEventStream feed(Model model, String sse) {
        AssistantMessageEventStream out = new AssistantMessageEventStream();
        OpenAIStreamParser parser = new OpenAIStreamParser(model, out);
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
