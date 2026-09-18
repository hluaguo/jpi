package dev.jpi.json;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeInfo.As;
import com.fasterxml.jackson.annotation.JsonTypeInfo.Id;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import dev.jpi.agent.AgentEvent;
import dev.jpi.ai.AssistantMessageEvent;
import dev.jpi.ai.Content;
import dev.jpi.ai.Message;

import java.util.List;

/**
 * The JSON wire contract for {@link Message}s and {@link AgentEvent}s — the payload
 * format RPC consumers (the DataForge SSE bridge) stream and store.
 *
 * <p><em>Why mixins instead of annotations on the model:</em> the core types stay
 * Jackson-free and this file stays the single, self-contained description of the wire
 * vocabulary: a {@code type} discriminator on every sealed hierarchy (names matching
 * pi's RPC/event vocabulary, e.g. {@code toolResult}, {@code text_delta},
 * {@code message_start}) and camelCase field names taken from the record components.
 * Unknown fields are tolerated on read so older readers survive newer writers.
 * Enum values serialize as their Java names ({@code TOOL_USE}), a stable spelling
 * pinned by the golden files.
 */
public final class Json {

    public static final ObjectMapper MAPPER = JsonMapper.builder()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .addMixIn(Message.class, Mixins.MessageMixin.class)
            .addMixIn(Content.class, Mixins.ContentMixin.class)
            .addMixIn(AssistantMessageEvent.class, Mixins.AssistantMessageEventMixin.class)
            .addMixIn(AgentEvent.class, Mixins.AgentEventMixin.class)
            .build();

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("failed to serialize to JSON", e);
        }
    }

    public static <T> T read(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("failed to parse JSON as " + type.getSimpleName(), e);
        }
    }

    public static JsonNode readTree(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("failed to parse JSON tree", e);
        }
    }

    public static <T> List<T> readList(String json, Class<T> elementType) {
        try {
            return MAPPER.readValue(json, MAPPER.getTypeFactory().constructCollectionType(List.class, elementType));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("failed to parse JSON as list of " + elementType.getSimpleName(), e);
        }
    }

    private Json() {
    }

    /** The wire vocabulary, one mixin per sealed hierarchy. */
    private static final class Mixins {

        @JsonTypeInfo(use = Id.NAME, include = As.PROPERTY, property = "type")
        @JsonSubTypes({
                @JsonSubTypes.Type(value = dev.jpi.ai.UserMessage.class, name = "user"),
                @JsonSubTypes.Type(value = dev.jpi.ai.AssistantMessage.class, name = "assistant"),
                @JsonSubTypes.Type(value = dev.jpi.ai.ToolResultMessage.class, name = "toolResult"),
        })
        interface MessageMixin {
        }

        @JsonTypeInfo(use = Id.NAME, include = As.PROPERTY, property = "type")
        @JsonSubTypes({
                @JsonSubTypes.Type(value = Content.Text.class, name = "text"),
                @JsonSubTypes.Type(value = Content.Image.class, name = "image"),
                @JsonSubTypes.Type(value = Content.Thinking.class, name = "thinking"),
                @JsonSubTypes.Type(value = Content.ToolCall.class, name = "toolCall"),
        })
        interface ContentMixin {
        }

        @JsonTypeInfo(use = Id.NAME, include = As.PROPERTY, property = "type")
        @JsonSubTypes({
                @JsonSubTypes.Type(value = AssistantMessageEvent.Start.class, name = "start"),
                @JsonSubTypes.Type(value = AssistantMessageEvent.TextStart.class, name = "text_start"),
                @JsonSubTypes.Type(value = AssistantMessageEvent.TextDelta.class, name = "text_delta"),
                @JsonSubTypes.Type(value = AssistantMessageEvent.TextEnd.class, name = "text_end"),
                @JsonSubTypes.Type(value = AssistantMessageEvent.ThinkingStart.class, name = "thinking_start"),
                @JsonSubTypes.Type(value = AssistantMessageEvent.ThinkingDelta.class, name = "thinking_delta"),
                @JsonSubTypes.Type(value = AssistantMessageEvent.ThinkingEnd.class, name = "thinking_end"),
                @JsonSubTypes.Type(value = AssistantMessageEvent.ToolCallStart.class, name = "toolcall_start"),
                @JsonSubTypes.Type(value = AssistantMessageEvent.ToolCallDelta.class, name = "toolcall_delta"),
                @JsonSubTypes.Type(value = AssistantMessageEvent.ToolCallEnd.class, name = "toolcall_end"),
                @JsonSubTypes.Type(value = AssistantMessageEvent.Done.class, name = "done"),
                @JsonSubTypes.Type(value = AssistantMessageEvent.Error.class, name = "error"),
        })
        interface AssistantMessageEventMixin {

            /** Derived from {@code type} (done/error); excluded from the wire. */
            @JsonIgnore
            boolean isTerminal();
        }

        @JsonTypeInfo(use = Id.NAME, include = As.PROPERTY, property = "type")
        @JsonSubTypes({
                @JsonSubTypes.Type(value = AgentEvent.Start.class, name = "agent_start"),
                @JsonSubTypes.Type(value = AgentEvent.TurnStart.class, name = "turn_start"),
                @JsonSubTypes.Type(value = AgentEvent.MessageStart.class, name = "message_start"),
                @JsonSubTypes.Type(value = AgentEvent.MessageUpdate.class, name = "message_update"),
                @JsonSubTypes.Type(value = AgentEvent.MessageEnd.class, name = "message_end"),
                @JsonSubTypes.Type(value = AgentEvent.ToolExecutionStart.class, name = "tool_execution_start"),
                @JsonSubTypes.Type(value = AgentEvent.ToolExecutionUpdate.class, name = "tool_execution_update"),
                @JsonSubTypes.Type(value = AgentEvent.ToolExecutionEnd.class, name = "tool_execution_end"),
                @JsonSubTypes.Type(value = AgentEvent.TurnEnd.class, name = "turn_end"),
                @JsonSubTypes.Type(value = AgentEvent.End.class, name = "agent_end"),
        })
        interface AgentEventMixin {
        }

        private Mixins() {
        }
    }
}
