package dev.jpi.ai.providers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import dev.jpi.ai.AssistantMessage;
import dev.jpi.ai.AssistantMessageEvent;
import dev.jpi.ai.AssistantMessageEventStream;
import dev.jpi.ai.Content;
import dev.jpi.ai.Model;
import dev.jpi.ai.StopReason;
import dev.jpi.ai.Usage;

/**
 * Turns Anthropic Messages SSE events ({@code message_start}, {@code content_block_*},
 * {@code message_delta}, {@code message_stop}, {@code error}) into
 * {@link AssistantMessageEvent}s, assembling partials and tool-call argument JSON from
 * the deltas. Terminal events end the stream; failures are data.
 */
final class AnthropicStreamParser {

    private final AssistantMessageEventStream out;
    private final AssistantMessage initial;
    private final List<Content> content = new ArrayList<>();
    private final Map<Integer, String> blockType = new HashMap<>();
    private final Map<Integer, StringBuilder> toolJson = new HashMap<>();
    private StopReason stopReason;
    private Usage usage = Usage.ZERO;
    private boolean terminal;

    AnthropicStreamParser(Model model, AssistantMessageEventStream out) {
        this.out = out;
        this.initial = AssistantMessage.pending(model);
    }

    /** Consumes one parsed SSE event; the event name is redundant with the data's type field. */
    void sseEvent(String eventName, String data) {
        try {
            handle(Json.MAPPER.readTree(data));
        } catch (Exception e) {
            fail("failed to parse provider event: " + e.getMessage());
        }
    }

    /** Called when the underlying byte stream ends; flags a missing {@code message_stop}. */
    void complete() {
        if (!terminal) {
            fail("stream ended without message_stop");
        }
    }

    private void handle(JsonNode node) {
        switch (node.path("type").asText()) {
            case "message_start" -> {
                usage = readUsage(node.path("message").path("usage"), usage, true);
                out.push(new AssistantMessageEvent.Start(partial()));
            }
            case "content_block_start" -> {
                int index = node.path("index").asInt();
                JsonNode block = node.path("content_block");
                switch (block.path("type").asText()) {
                    case "text" -> {
                        blockType.put(index, "text");
                        content.add(new Content.Text(""));
                        out.push(new AssistantMessageEvent.TextStart(partial()));
                    }
                    case "thinking" -> {
                        blockType.put(index, "thinking");
                        content.add(new Content.Thinking(""));
                        out.push(new AssistantMessageEvent.ThinkingStart(partial()));
                    }
                    case "tool_use" -> {
                        blockType.put(index, "tool_use");
                        toolJson.put(index, new StringBuilder());
                        content.add(new Content.ToolCall(block.path("id").asText(),
                                block.path("name").asText(), Map.of()));
                        out.push(new AssistantMessageEvent.ToolCallStart(
                                block.path("id").asText(), block.path("name").asText(), partial()));
                    }
                    default -> {
                        // unknown block type: track so its stop doesn't confuse the state machine
                        blockType.put(index, block.path("type").asText());
                    }
                }
            }
            case "content_block_delta" -> {
                int index = node.path("index").asInt();
                JsonNode delta = node.path("delta");
                switch (delta.path("type").asText()) {
                    case "text_delta" -> {
                        appendText(index, delta.path("text").asText());
                        out.push(new AssistantMessageEvent.TextDelta(delta.path("text").asText(), partial()));
                    }
                    case "thinking_delta" -> {
                        appendThinking(index, delta.path("thinking").asText());
                        out.push(new AssistantMessageEvent.ThinkingDelta(delta.path("thinking").asText(), partial()));
                    }
                    case "signature_delta" -> {
                        if (content.get(index) instanceof Content.Thinking thinking) {
                            content.set(index, new Content.Thinking(thinking.thinking(),
                                    delta.path("signature").asText(), thinking.redacted()));
                        }
                    }
                    case "input_json_delta" -> {
                        String chunk = delta.path("partial_json").asText();
                        toolJson.get(index).append(chunk);
                        out.push(new AssistantMessageEvent.ToolCallDelta(toolCallId(index), chunk, partial()));
                    }
                    default -> {
                        // unknown delta type: ignore
                    }
                }
            }
            case "content_block_stop" -> {
                int index = node.path("index").asInt();
                switch (blockType.getOrDefault(index, "")) {
                    case "text" -> out.push(new AssistantMessageEvent.TextEnd(partial()));
                    case "thinking" -> out.push(new AssistantMessageEvent.ThinkingEnd(partial()));
                    case "tool_use" -> {
                        String id = toolCallId(index);
                        Map<String, Object> arguments = parseArguments(toolJson.get(index).toString());
                        content.set(index, new Content.ToolCall(id, toolCallName(index), arguments));
                        out.push(new AssistantMessageEvent.ToolCallEnd(id, partial()));
                    }
                    default -> {
                        // unknown block: ignore
                    }
                }
            }
            case "message_delta" -> {
                String reason = node.path("delta").path("stop_reason").asText(null);
                if (reason != null) {
                    stopReason = mapStopReason(reason);
                }
                usage = readUsage(node.path("usage"), usage, false);
            }
            case "message_stop" -> {
                out.push(new AssistantMessageEvent.Done(finalMessage(stopReason == null ? StopReason.STOP : stopReason)));
                terminal = true;
            }
            case "error" -> {
                fail(node.path("error").path("message").asText("provider error"));
            }
            default -> {
                // keep-alives (ping) and unknown types are ignored
            }
        }
    }

    private AssistantMessage partial() {
        return initial.withContent(List.copyOf(content));
    }

    private AssistantMessage finalMessage(StopReason reason) {
        return partial().withStopReason(reason).withUsage(usage);
    }

    private void fail(String errorMessage) {
        if (!terminal) {
            out.push(new AssistantMessageEvent.Error(
                    finalMessage(StopReason.ERROR).withErrorMessage(errorMessage)));
            terminal = true;
        }
    }

    private int toolCallIndex(String id) {
        for (int i = 0; i < content.size(); i++) {
            if (content.get(i) instanceof Content.ToolCall call && call.id().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    private String toolCallId(int index) {
        return content.get(index) instanceof Content.ToolCall call ? call.id() : "";
    }

    private String toolCallName(int index) {
        return content.get(index) instanceof Content.ToolCall call ? call.name() : "";
    }

    private void appendText(int index, String text) {
        if (content.get(index) instanceof Content.Text t) {
            content.set(index, new Content.Text(t.text() + text));
        }
    }

    private void appendThinking(int index, String thinking) {
        if (content.get(index) instanceof Content.Thinking t) {
            content.set(index, new Content.Thinking(t.thinking() + thinking, t.signature(), t.redacted()));
        }
    }

    private static Map<String, Object> parseArguments(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return Json.MAPPER.readValue(json, Map.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("malformed tool arguments: " + json, e);
        }
    }

    private static StopReason mapStopReason(String anthropicReason) {
        return switch (anthropicReason) {
            case "max_tokens" -> StopReason.LENGTH;
            case "tool_use" -> StopReason.TOOL_USE;
            case "end_turn", "stop_sequence", "refusal" -> StopReason.STOP;
            default -> StopReason.STOP;
        };
    }

    /**
     * Merges an Anthropic usage object into the running total: {@code message_start}
     * carries input-side counts, {@code message_delta} carries the final output count.
     */
    private static Usage readUsage(JsonNode node, Usage current, boolean fromMessageStart) {
        if (node.isMissingNode() || node.isNull()) {
            return current;
        }
        long input = node.hasNonNull("input_tokens") ? node.path("input_tokens").asLong() : current.input();
        long output = node.hasNonNull("output_tokens") ? node.path("output_tokens").asLong() : current.output();
        long cacheRead = node.hasNonNull("cache_read_input_tokens")
                ? node.path("cache_read_input_tokens").asLong() : current.cacheRead();
        long cacheWrite = node.hasNonNull("cache_creation_input_tokens")
                ? node.path("cache_creation_input_tokens").asLong() : current.cacheWrite();
        if (fromMessageStart && output == 0) {
            output = current.output();
        }
        return new Usage(input, output, cacheRead, cacheWrite, 0, 0, 0, 0);
    }
}
