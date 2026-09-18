package dev.jpi.ai.providers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import dev.jpi.ai.AssistantMessage;
import dev.jpi.ai.AssistantMessageEventStream;
import dev.jpi.ai.Content;
import dev.jpi.ai.Context;
import dev.jpi.ai.ErrorClassifier;
import dev.jpi.ai.ErrorKind;
import dev.jpi.ai.Message;
import dev.jpi.ai.Model;
import dev.jpi.ai.StopReason;
import dev.jpi.ai.StreamFn;
import dev.jpi.ai.StreamOptions;
import dev.jpi.ai.ToolResultMessage;
import dev.jpi.ai.UserMessage;

/**
 * OpenAI-compatible chat-completions adapter: builds {@code POST /v1/chat/completions}
 * requests and maps the SSE chunk stream to the unified
 * {@link dev.jpi.ai.AssistantMessageEvent} protocol. Works with any
 * OpenAI-compatible endpoint (baseUrl is configurable). Failures become terminal
 * error events — never exceptions.
 */
public final class OpenAICompletionsProvider implements StreamFn {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    private final String apiKey;
    private final String baseUrl;

    public OpenAICompletionsProvider(String apiKey) {
        this(apiKey, "https://api.openai.com");
    }

    public OpenAICompletionsProvider(String apiKey, String baseUrl) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
    }

    @Override
    public AssistantMessageEventStream stream(Model model, Context context, StreamOptions options) {
        AssistantMessageEventStream out = new AssistantMessageEventStream();
        String effectiveKey = options.apiKey() != null ? options.apiKey() : apiKey;
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/chat/completions"))
                    .timeout(Duration.ofSeconds(600))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + effectiveKey)
                    .POST(HttpRequest.BodyPublishers.ofString(Json.write(buildRequest(model, context))))
                    .build();
        } catch (Exception e) {
            fail(out, failure(model, "failed to build request: " + e.getMessage(), ErrorKind.UNKNOWN));
            return out;
        }

        http.sendAsync(request, HttpResponse.BodyHandlers.ofLines())
                .thenAccept(response -> {
                    if (response.statusCode() != 200) {
                        String details = response.body().collect(Collectors.joining());
                        fail(out, failure(model, response.statusCode(), details));
                        return;
                    }
                    OpenAIStreamParser parser = new OpenAIStreamParser(model, out);
                    SseParser sse = new SseParser(parser::sseEvent);
                    try (var lines = response.body()) {
                        lines.forEach(sse::processLine);
                    } catch (Exception e) {
                        fail(out, failure(model, "failed reading response stream: " + e.getMessage(),
                                ErrorClassifier.classify(e)));
                        return;
                    }
                    sse.end();
                    parser.complete();
                })
                .exceptionally(ex -> {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    fail(out, failure(model, "request failed: " + cause.getMessage(),
                            ErrorClassifier.classify(cause)));
                    return null;
                });
        return out;
    }

    private static void fail(AssistantMessageEventStream out, AssistantMessage message) {
        if (!out.result().isDone()) {
            out.push(new dev.jpi.ai.AssistantMessageEvent.Error(message));
        }
    }

    /** The error message for an HTTP-level rejection: message and classification from status + body. */
    static AssistantMessage failure(Model model, int statusCode, String body) {
        return failure(model, "HTTP " + statusCode + ": " + truncate(body),
                ErrorClassifier.classify(statusCode, body));
    }

    /** The error message for a non-HTTP failure (request build, transport, stream IO). */
    static AssistantMessage failure(Model model, String message, ErrorKind kind) {
        return AssistantMessage.pending(model)
                .withStopReason(StopReason.ERROR)
                .withErrorMessage(message)
                .withDiagnostics(kind);
    }

    private static String truncate(String text) {
        return text.length() <= 500 ? text : text.substring(0, 500) + "…";
    }

    /**
     * Builds the chat-completions request body: system message, role messages
     * ({@code tool} role for tool results), and function tool declarations.
     */
    static Map<String, Object> buildRequest(Model model, Context context) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", model.id());
        request.put("stream", true);
        request.put("stream_options", mapOf("include_usage", true));
        List<Map<String, Object>> messages = new ArrayList<>();
        if (context.systemPrompt() != null) {
            messages.add(mapOf("role", "system", "content", context.systemPrompt()));
        }
        for (Message message : context.messages()) {
            messages.add(toWireMessage(message));
        }
        request.put("messages", messages);
        if (!context.tools().isEmpty()) {
            List<Map<String, Object>> tools = new ArrayList<>();
            for (dev.jpi.ai.Tool tool : context.tools()) {
                tools.add(mapOf("type", "function", "function",
                        mapOf("name", tool.name(), "description", tool.description(),
                                "parameters", tool.parameters())));
            }
            request.put("tools", tools);
        }
        return request;
    }

    private static Map<String, Object> toWireMessage(Message message) {
        if (message instanceof UserMessage user) {
            Map<String, Object> wire = new LinkedHashMap<>();
            wire.put("role", "user");
            wire.put("content", toWireContent(user.content()));
            return wire;
        }
        if (message instanceof dev.jpi.ai.AssistantMessage assistant) {
            Map<String, Object> wire = new LinkedHashMap<>();
            wire.put("role", "assistant");
            wire.put("content", textOf(assistant.content()));
            List<Map<String, Object>> toolCalls = new ArrayList<>();
            for (Content block : assistant.content()) {
                if (block instanceof Content.ToolCall call) {
                    toolCalls.add(mapOf("id", call.id(), "type", "function", "function",
                            mapOf("name", call.name(), "arguments", Json.write(call.arguments()))));
                }
            }
            if (!toolCalls.isEmpty()) {
                wire.put("tool_calls", toolCalls);
            }
            return wire;
        }
        if (message instanceof ToolResultMessage toolResult) {
            Map<String, Object> wire = new LinkedHashMap<>();
            wire.put("role", "tool");
            wire.put("tool_call_id", toolResult.toolCallId());
            wire.put("content", textOf(toolResult.content()));
            return wire;
        }
        throw new IllegalArgumentException("unsupported message type: " + message.getClass());
    }

    /** A single text block collapses to a plain string; otherwise a block array is sent. */
    private static Object toWireContent(List<Content> blocks) {
        if (blocks.size() == 1 && blocks.get(0) instanceof Content.Text only) {
            return only.text();
        }
        List<Map<String, Object>> wire = new ArrayList<>();
        for (Content block : blocks) {
            if (block instanceof Content.Text text) {
                wire.add(mapOf("type", "text", "text", text.text()));
            } else if (block instanceof Content.Image image) {
                wire.add(mapOf("type", "image_url", "image_url",
                        mapOf("url", "data:" + image.mimeType() + ";base64," + image.data())));
            }
        }
        return wire;
    }

    private static String textOf(List<Content> blocks) {
        StringBuilder text = new StringBuilder();
        for (Content block : blocks) {
            if (block instanceof Content.Text t) {
                text.append(t.text());
            }
        }
        return text.toString();
    }

    private static Map<String, Object> mapOf(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }
}
