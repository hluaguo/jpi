package dev.jpi.ai.providers;

import dev.jpi.ai.AssistantMessage;
import dev.jpi.ai.AssistantMessageEvent;
import dev.jpi.ai.AssistantMessageEventStream;
import dev.jpi.ai.Content;
import dev.jpi.ai.Context;
import dev.jpi.ai.Model;
import dev.jpi.ai.StopReason;
import dev.jpi.ai.StreamFn;
import dev.jpi.ai.StreamOptions;
import dev.jpi.ai.Usage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * A {@link StreamFn} built from canned responses — the test and demo backbone for the
 * agent loop. Each builder method scripts one LLM call; successive {@link #stream}
 * calls pop the scripts in order and record the calls for inspection.
 *
 * <pre>{@code
 * ScriptedProvider provider = ScriptedProvider.builder()
 *         .text("Hello")
 *         .toolCall("call_1", "read", Map.of("path", "a.txt"))
 *         .build();
 * }</pre>
 */
public final class ScriptedProvider implements StreamFn {

    /** One recorded LLM call, for assertions. */
    public record LlmCall(Model model, Context context, StreamOptions options) {
    }

    private interface Script extends BiFunction<Model, Context, AssistantMessageEventStream> {
    }

    private final List<Script> scripts;
    private final List<LlmCall> calls = new ArrayList<>();
    private int next;

    private ScriptedProvider(List<Script> scripts) {
        this.scripts = List.copyOf(scripts);
    }

    public static Builder builder() {
        return new Builder();
    }

    public synchronized List<LlmCall> calls() {
        return List.copyOf(calls);
    }

    @Override
    public synchronized AssistantMessageEventStream stream(Model model, Context context, StreamOptions options) {
        if (next >= scripts.size()) {
            throw new IllegalStateException("no scripted response for call " + (next + 1));
        }
        calls.add(new LlmCall(model, context, options));
        return scripts.get(next++).apply(model, context);
    }

    public static final class Builder {
        private final List<Script> scripts = new ArrayList<>();

        private Builder() {
        }

        /** One call producing a single text block ending with {@code done(STOP)}.
         *
         * @param usage provider-reported token counts; the loop/UI treat these as authoritative
         */
        public Builder text(String text, Usage usage) {
            return add((model, context) -> {
                AssistantMessageEventStream stream = new AssistantMessageEventStream();
                AssistantMessage partial = AssistantMessage.pending(model);
                stream.push(new AssistantMessageEvent.Start(partial));
                partial = partial.withContent(List.of(new Content.Text("")));
                stream.push(new AssistantMessageEvent.TextStart(partial));
                partial = partial.withContent(List.of(new Content.Text(text))).withUsage(usage);
                stream.push(new AssistantMessageEvent.TextDelta(text, partial));
                stream.push(new AssistantMessageEvent.TextEnd(partial));
                partial = partial.withStopReason(StopReason.STOP);
                stream.push(new AssistantMessageEvent.Done(partial));
                return stream;
            });
        }

        public Builder text(String text) {
            return text(text, Usage.ZERO);
        }

        /** One call producing several tool calls (one response), ending with {@code done(TOOL_USE)}. */
        public Builder toolCalls(List<Content.ToolCall> calls) {
            return add((model, context) -> {
                AssistantMessageEventStream stream = new AssistantMessageEventStream();
                AssistantMessage partial = AssistantMessage.pending(model);
                stream.push(new AssistantMessageEvent.Start(partial));
                List<Content> content = new ArrayList<>();
                for (Content.ToolCall call : calls) {
                    content.add(call);
                    stream.push(new AssistantMessageEvent.ToolCallStart(call.id(), call.name(), partial.withContent(content)));
                    stream.push(new AssistantMessageEvent.ToolCallDelta(call.id(), Json.write(call.arguments()),
                            partial.withContent(content)));
                    stream.push(new AssistantMessageEvent.ToolCallEnd(call.id(), partial.withContent(content)));
                }
                partial = partial.withContent(content);
                partial = partial.withStopReason(StopReason.TOOL_USE);
                stream.push(new AssistantMessageEvent.Done(partial));
                return stream;
            });
        }

        /** One call producing a single tool call ending with {@code done(TOOL_USE)}. */
        public Builder toolCall(String id, String name, Map<String, Object> arguments) {
            String argsJson = Json.write(arguments);
            return add((model, context) -> {
                AssistantMessageEventStream stream = new AssistantMessageEventStream();
                AssistantMessage partial = AssistantMessage.pending(model);
                stream.push(new AssistantMessageEvent.Start(partial));
                partial = partial.withContent(List.of(new Content.ToolCall(id, name, Map.of())));
                stream.push(new AssistantMessageEvent.ToolCallStart(id, name, partial));
                stream.push(new AssistantMessageEvent.ToolCallDelta(id, argsJson, partial));
                partial = partial.withContent(List.of(new Content.ToolCall(id, name, arguments)));
                stream.push(new AssistantMessageEvent.ToolCallEnd(id, partial));
                partial = partial.withStopReason(StopReason.TOOL_USE);
                stream.push(new AssistantMessageEvent.Done(partial));
                return stream;
            });
        }

        /** One call producing a tool call whose stream ends truncated ({@code done(LENGTH)}). */
        public Builder truncatedToolCall(String id, String name, Map<String, Object> arguments) {
            String argsJson = Json.write(arguments);
            return add((model, context) -> {
                AssistantMessageEventStream stream = new AssistantMessageEventStream();
                AssistantMessage partial = AssistantMessage.pending(model);
                stream.push(new AssistantMessageEvent.Start(partial));
                partial = partial.withContent(List.of(new Content.ToolCall(id, name, Map.of())));
                stream.push(new AssistantMessageEvent.ToolCallStart(id, name, partial));
                stream.push(new AssistantMessageEvent.ToolCallDelta(id, argsJson, partial));
                partial = partial.withContent(List.of(new Content.ToolCall(id, name, arguments)));
                stream.push(new AssistantMessageEvent.ToolCallEnd(id, partial));
                partial = partial.withStopReason(StopReason.LENGTH);
                stream.push(new AssistantMessageEvent.Done(partial));
                return stream;
            });
        }

        /** One call whose stream terminates immediately with an error message ({@code stopReason == ERROR}). */
        public Builder error(String message) {
            return add((model, context) -> {
                AssistantMessageEventStream stream = new AssistantMessageEventStream();
                AssistantMessage partial = AssistantMessage.pending(model)
                        .withStopReason(StopReason.ERROR)
                        .withErrorMessage(message);
                stream.push(new AssistantMessageEvent.Error(partial));
                return stream;
            });
        }

        /** One call whose stream terminates immediately as aborted ({@code stopReason == ABORTED}). */
        public Builder aborted() {
            return add((model, context) -> {
                AssistantMessageEventStream stream = new AssistantMessageEventStream();
                AssistantMessage partial = AssistantMessage.pending(model)
                        .withStopReason(StopReason.ABORTED);
                stream.push(new AssistantMessageEvent.Error(partial));
                return stream;
            });
        }

        /** One call emitting the given events verbatim (session replay); the last must be terminal. */
        public Builder events(List<AssistantMessageEvent> events) {
            return add((model, context) -> {
                AssistantMessageEventStream stream = new AssistantMessageEventStream();
                events.forEach(stream::push);
                return stream;
            });
        }

        private Builder add(Script script) {
            scripts.add(script);
            return this;
        }

        public ScriptedProvider build() {
            return new ScriptedProvider(scripts);
        }
    }
}
