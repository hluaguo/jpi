package dev.jpi.ai.providers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
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
 * Anthropic Messages API adapter: builds {@code POST /v1/messages} requests and maps
 * the SSE response to the unified {@link dev.jpi.ai.AssistantMessageEvent} protocol.
 * Failures (HTTP errors, network errors, malformed events) become terminal error
 * events — never exceptions.
 */
public final class AnthropicProvider implements StreamFn {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    private final String apiKey;
    private final String baseUrl;

    public AnthropicProvider(String apiKey) {
        this(apiKey, "https://api.anthropic.com");
    }

    public AnthropicProvider(String apiKey, String baseUrl) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
    }

    @Override
    public AssistantMessageEventStream stream(Model model, Context context, StreamOptions options) {
        AssistantMessageEventStream out = new AssistantMessageEventStream();
        String effectiveKey = options.apiKey() != null ? options.apiKey() : apiKey;
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/messages"))
                    .timeout(Duration.ofSeconds(600))
                    .header("Content-Type", "application/json")
                    .header("x-api-key", effectiveKey)
                    .header("anthropic-version", "2023-06-01")
                    .POST(HttpRequest.BodyPublishers.ofString(Json.write(buildRequest(model, context, options.promptCaching()))))
                    .build();
        } catch (Exception e) {
            fail(out, failure(model, "failed to build request: " + e.getMessage(), ErrorKind.UNKNOWN));
            return out;
        }

        CompletableFuture<HttpResponse<java.util.stream.Stream<String>>> future =
                http.sendAsync(request, HttpResponse.BodyHandlers.ofLines());
        if (options.cancel() != null) {
            // wire-level cancellation (pi passes the signal to fetch)
            options.cancel().onAbort(() -> future.cancel(true));
        }
        future.thenAccept(response -> {
                    if (response.statusCode() != 200) {
                        String details = response.body().collect(Collectors.joining());
                        fail(out, failure(model, response.statusCode(), details));
                        return;
                    }
                    AnthropicStreamParser parser = new AnthropicStreamParser(model, out);
                    SseParser sse = new SseParser(parser::sseEvent);
                    try (var lines = response.body()) {
                        pumpSse(lines.iterator(), sse::processLine, options.cancel());
                    } catch (RequestAbortedException e) {
                        fail(out, abortedFailure(model));
                        return;
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
                    if (cause instanceof java.util.concurrent.CancellationException
                            || (options.cancel() != null && options.cancel().isAborted())) {
                        // pi maps a wire-level abort to aborted, not error
                        fail(out, abortedFailure(model));
                        return null;
                    }
                    fail(out, failure(model, "request failed: " + cause.getMessage(),
                            ErrorClassifier.classify(cause)));
                    return null;
                });
        return out;
    }

    /**
     * Feeds body lines to the SSE parser, observing cancellation between lines: an
     * in-flight generation must end ABORTED promptly instead of surfacing only when
     * the model finishes (pi: {@code iterateSseMessages} checks the signal on every
     * read and throws "Request was aborted").
     */
    static void pumpSse(Iterator<String> lines, java.util.function.Consumer<String> processLine,
                        dev.jpi.util.CancellationToken cancel) {
        while (lines.hasNext()) {
            if (cancel != null && cancel.isAborted()) {
                throw new RequestAbortedException();
            }
            processLine.accept(lines.next());
        }
    }

    /** An aborted request is data: stopReason ABORTED, never a thrown error. */
    static AssistantMessage abortedFailure(Model model) {
        return AssistantMessage.pending(model)
                .withStopReason(StopReason.ABORTED)
                .withErrorMessage("Request was aborted");
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
     * Builds the {@code /v1/messages} request body: system prompt, wire messages
     * (tool results ride on user messages), and tool declarations.
     */
    static Map<String, Object> buildRequest(Model model, Context context) {
        return buildRequest(model, context, true);
    }

    /**
     * Builds the request body as above; with {@code promptCaching}, three ephemeral
     * cache breakpoints are marked — the system prompt (as a block array), the last
     * tool, and the last block of the last user message — so multi-turn runs hit the
     * provider's prefix cache instead of repaying full input price per turn.
     */
    static Map<String, Object> buildRequest(Model model, Context context, boolean promptCaching) {
        Map<String, Object> cacheControl = promptCaching ? Map.of("type", "ephemeral") : null;
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", model.id());
        request.put("max_tokens", model.maxTokens());
        request.put("stream", true);
        if (context.systemPrompt() != null) {
            if (cacheControl == null) {
                request.put("system", sanitizeSurrogates(context.systemPrompt()));
            } else {
                Map<String, Object> block = new LinkedHashMap<>();
                block.put("type", "text");
                block.put("text", sanitizeSurrogates(context.systemPrompt()));
                block.put("cache_control", cacheControl);
                request.put("system", List.of(block));
            }
        }
        List<Map<String, Object>> messages = new ArrayList<>();
        List<Message> source = context.messages();
        for (int i = 0; i < source.size(); i++) {
            if (source.get(i) instanceof ToolResultMessage) {
                // pi: all consecutive tool results merge into ONE user message with
                // multiple tool_result blocks — the shape the Messages API documents;
                // one user turn per result would send N adjacent user messages for
                // an N-call parallel batch
                List<Map<String, Object>> blocks = new ArrayList<>();
                while (i < source.size() && source.get(i) instanceof ToolResultMessage toolResult) {
                    blocks.add(toolResultBlock(toolResult));
                    i++;
                }
                i--;
                Map<String, Object> wire = new LinkedHashMap<>();
                wire.put("role", "user");
                wire.put("content", blocks);
                messages.add(wire);
                continue;
            }
            messages.add(toWireMessage(source.get(i)));
        }
        if (cacheControl != null && !messages.isEmpty()) {
            Map<String, Object> last = messages.get(messages.size() - 1);
            if ("user".equals(last.get("role"))) {
                markLastBlock((List<Map<String, Object>>) last.get("content"), cacheControl);
            }
        }
        request.put("messages", messages);
        if (!context.tools().isEmpty()) {
            List<Map<String, Object>> tools = new ArrayList<>();
            for (dev.jpi.ai.Tool tool : context.tools()) {
                Map<String, Object> wire = new LinkedHashMap<>();
                wire.put("name", tool.name());
                wire.put("description", tool.description());
                wire.put("input_schema", tool.parameters());
                tools.add(wire);
            }
            if (cacheControl != null) {
                markLastBlock(tools, cacheControl);
            }
            request.put("tools", tools);
        }
        return request;
    }

    /** Adds {@code cacheControl} to the final block in place; the wire maps are locally built. */
    private static void markLastBlock(List<Map<String, Object>> blocks, Map<String, Object> cacheControl) {
        if (!blocks.isEmpty()) {
            blocks.get(blocks.size() - 1).put("cache_control", cacheControl);
        }
    }

    /**
     * Drops unpaired UTF-16 surrogates: tool output decoded from non-UTF-8 bytes can
     * carry them, and Anthropic rejects the request. Properly paired characters
     * (emoji and all non-BMP text) pass through untouched.
     */
    static String sanitizeSurrogates(String text) {
        StringBuilder out = null;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean high = c >= 0xD800 && c <= 0xDBFF;
            boolean low = c >= 0xDC00 && c <= 0xDFFF;
            boolean paired = high && i + 1 < text.length()
                    && text.charAt(i + 1) >= 0xDC00 && text.charAt(i + 1) <= 0xDFFF;
            if ((high || low) && !paired) {
                if (out == null) {
                    out = new StringBuilder(text.length()).append(text, 0, i);
                }
                continue;
            }
            if (out != null) {
                out.append(c);
                if (paired) {
                    out.append(text.charAt(++i));
                }
            } else if (paired) {
                i++;
            }
        }
        return out == null ? text : out.toString();
    }

    private static Map<String, Object> toWireMessage(Message message) {
        if (message instanceof UserMessage user) {
            Map<String, Object> wire = new LinkedHashMap<>();
            wire.put("role", "user");
            wire.put("content", toWireBlocks(user.content()));
            return wire;
        }
        if (message instanceof AssistantMessage assistant) {
            Map<String, Object> wire = new LinkedHashMap<>();
            wire.put("role", "assistant");
            List<Map<String, Object>> content = new ArrayList<>();
            for (Content block : assistant.content()) {
                if (block instanceof Content.Text text) {
                    content.add(mapOf("type", "text", "text", sanitizeSurrogates(text.text())));
                } else if (block instanceof Content.Thinking thinking) {
                    Map<String, Object> wireBlock = new LinkedHashMap<>();
                    wireBlock.put("type", "thinking");
                    wireBlock.put("thinking", sanitizeSurrogates(thinking.thinking()));
                    if (thinking.signature() != null) {
                        wireBlock.put("signature", thinking.signature());
                    }
                    content.add(wireBlock);
                } else if (block instanceof Content.ToolCall call) {
                    content.add(mapOf("type", "tool_use", "id", call.id(), "name", call.name(),
                            "input", call.arguments()));
                }
            }
            wire.put("content", content);
            return wire;
        }
        if (message instanceof ToolResultMessage) {
            throw new AssertionError("tool results are merged by buildRequest, never converted one-by-one");
        }
        throw new IllegalArgumentException("unsupported message type: " + message.getClass());
    }

    /** One {@code tool_result} block; consecutive blocks share a single user message. */
    private static Map<String, Object> toolResultBlock(ToolResultMessage toolResult) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "tool_result");
        block.put("tool_use_id", toolResult.toolCallId());
        if (toolResult.isError()) {
            block.put("is_error", true);
        }
        block.put("content", toWireBlocks(toolResult.content()));
        return block;
    }

    private static List<Map<String, Object>> toWireBlocks(List<Content> blocks) {
        List<Map<String, Object>> wire = new ArrayList<>();
        for (Content block : blocks) {
            if (block instanceof Content.Text text) {
                wire.add(mapOf("type", "text", "text", sanitizeSurrogates(text.text())));
            } else if (block instanceof Content.Image image) {
                wire.add(mapOf("type", "image", "source",
                        mapOf("type", "base64", "media_type", image.mimeType(), "data", image.data())));
            }
        }
        return wire;
    }

    private static Map<String, Object> mapOf(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }
}
