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
 * Turns OpenAI chat-completions SSE chunks into {@link AssistantMessageEvent}s:
 * text deltas stream as a text block; {@code tool_calls} deltas are assembled by
 * their {@code index}; block ends are synthesized at {@code finish_reason}; the
 * usage chunk (which arrives after finish) is folded into the final message.
 */
final class OpenAIStreamParser {

    private final AssistantMessageEventStream out;
    private final AssistantMessage initial;
    private final List<Content> content = new ArrayList<>();
    private final Map<Integer, String> toolIdByIndex = new HashMap<>();
    private final Map<Integer, StringBuilder> toolArgsByIndex = new HashMap<>();
    private final Map<String, Integer> contentIndexById = new HashMap<>();
    private boolean startEmitted;
    private int openTextBlock = -1;
    private boolean finishReceived;
    private StopReason stopReason;
    private Usage usage = Usage.ZERO;
    private boolean terminal;

    OpenAIStreamParser(Model model, AssistantMessageEventStream out) {
        this.out = out;
        this.initial = AssistantMessage.pending(model);
    }

    /** Consumes one parsed SSE data payload. */
    void sseEvent(String eventName, String data) {
        if ("[DONE]".equals(data.trim())) {
            finish();
            return;
        }
        try {
            handle(Json.MAPPER.readTree(data));
        } catch (Exception e) {
            fail("failed to parse provider chunk: " + e.getMessage());
        }
    }

    /** Called when the underlying byte stream ends; a missing {@code [DONE]} is an error. */
    void complete() {
        if (!terminal) {
            fail("stream ended without [DONE]");
        }
    }

    private void handle(JsonNode node) {
        if (terminal) {
            return; // stray chunks after [DONE] must not reach the finished stream
        }
        readUsage(node.path("usage"));

        JsonNode choices = node.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            return;
        }
        JsonNode choice = choices.get(0);
        JsonNode delta = choice.path("delta");

        if (!startEmitted) {
            startEmitted = true;
            out.push(new AssistantMessageEvent.Start(partial()));
        }

        if (delta.hasNonNull("content")) {
            String text = delta.path("content").asText();
            if (!text.isEmpty()) {
                if (openTextBlock < 0) {
                    content.add(new Content.Text(""));
                    openTextBlock = content.size() - 1;
                    out.push(new AssistantMessageEvent.TextStart(partial()));
                }
                Content.Text current = (Content.Text) content.get(openTextBlock);
                content.set(openTextBlock, new Content.Text(current.text() + text));
                out.push(new AssistantMessageEvent.TextDelta(text, partial()));
            }
        }

        for (JsonNode tc : delta.path("tool_calls")) {
            closeTextBlock();
            int index = tc.path("index").asInt();
            if (!toolIdByIndex.containsKey(index)) {
                String id = tc.path("id").asText();
                String name = tc.path("function").path("name").asText();
                toolIdByIndex.put(index, id);
                toolArgsByIndex.put(index, new StringBuilder());
                contentIndexById.put(id, content.size());
                content.add(new Content.ToolCall(id, name, Map.of()));
                out.push(new AssistantMessageEvent.ToolCallStart(id, name, partial()));
            }
            String chunk = tc.path("function").path("arguments").asText("");
            if (!chunk.isEmpty()) {
                toolArgsByIndex.get(index).append(chunk);
                out.push(new AssistantMessageEvent.ToolCallDelta(toolIdByIndex.get(index), chunk, partial()));
            }
        }

        String finishReason = choice.path("finish_reason").asText(null);
        if (finishReason != null) {
            stopReason = mapStopReason(finishReason);
            closeOpenBlocks();
            finishReceived = true;
        }
    }

    private void readUsage(JsonNode usageNode) {
        if (!usageNode.isObject() || usageNode.size() == 0) {
            return;
        }
        long input = usageNode.hasNonNull("prompt_tokens")
                ? usageNode.path("prompt_tokens").asLong() : usage.input();
        long output = usageNode.hasNonNull("completion_tokens")
                ? usageNode.path("completion_tokens").asLong() : usage.output();
        usage = new Usage(input, output, usage.cacheRead(), usage.cacheWrite(), 0, 0, 0, 0);
    }

    private void closeTextBlock() {
        if (openTextBlock >= 0) {
            out.push(new AssistantMessageEvent.TextEnd(partial()));
            openTextBlock = -1;
        }
    }

    /** Closes open blocks (tool calls parsed from their argument deltas). */
    private void closeOpenBlocks() {
        if (finishReceived) {
            return;
        }
        closeTextBlock();
        List<Integer> indices = new ArrayList<>(toolArgsByIndex.keySet());
        indices.sort(Integer::compareTo);
        for (int index : indices) {
            String id = toolIdByIndex.get(index);
            Map<String, Object> arguments = JsonRepair.parseArguments(toolArgsByIndex.get(index).toString());
            int contentIndex = contentIndexById.get(id);
            Content.ToolCall started = (Content.ToolCall) content.get(contentIndex);
            content.set(contentIndex, new Content.ToolCall(id, started.name(), arguments));
            out.push(new AssistantMessageEvent.ToolCallEnd(id, partial()));
        }
    }

    /** Emits the terminal event; an ERROR stop reason must ride on Error, not Done. */
    private void finish() {
        if (terminal) {
            return;
        }
        closeOpenBlocks();
        terminal = true;
        StopReason reason = stopReason == null ? StopReason.STOP : stopReason;
        if (reason == StopReason.ERROR) {
            out.push(new AssistantMessageEvent.Error(
                    finalMessage(reason).withErrorMessage("content filter stopped the response")));
        } else {
            out.push(new AssistantMessageEvent.Done(finalMessage(reason)));
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


    private static StopReason mapStopReason(String finishReason) {
        return switch (finishReason) {
            case "length" -> StopReason.LENGTH;
            case "tool_calls", "function_call" -> StopReason.TOOL_USE;
            case "content_filter" -> StopReason.ERROR;
            default -> StopReason.STOP;
        };
    }
}
