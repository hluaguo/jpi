package dev.jpi.ai.providers;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

import dev.jpi.ai.AssistantMessage;
import dev.jpi.ai.AssistantMessageEventStream;
import dev.jpi.ai.Content;
import dev.jpi.ai.Context;
import dev.jpi.ai.Model;
import dev.jpi.ai.StopReason;
import dev.jpi.ai.StreamOptions;
import dev.jpi.ai.UserMessage;
import dev.jpi.json.Json;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the {@link OpenAICompletionsProvider} wire contract against exchanges the
 * real DeepSeek API produced (see {@link RecordDeepSeekFixtures}): the request
 * mapping must keep producing exactly the body the live API accepted, and the
 * error mapping must digest the recorded non-200 body. The streaming path itself
 * is exercised end to end by {@code dev.jpi.e2e.EndToEndTest}.
 */
class DeepSeekFixtureContractTest {

    @Test
    void requestMappingStillProducesTheBodyTheLiveApiAccepted() throws Exception {
        String recorded = Files.readString(
                Path.of("src", "test", "resources", "fixtures", "deepseek", "chat-text.request.json"),
                StandardCharsets.UTF_8);

        // chat-text was recorded with this exact context (see the recorder)
        Context context = new Context("You are a concise assistant.",
                List.of(UserMessage.of(
                        "In exactly three short paragraphs, explain how a hash map handles collisions.")),
                List.of());
        JsonNode rebuilt = Json.MAPPER.valueToTree(OpenAICompletionsProvider.buildRequest(
                RecordDeepSeekFixtures.MODEL, context));

        assertEquals(Json.readTree(recorded), rebuilt,
                "request mapping drifted from what the live DeepSeek API accepted");
    }

    @Test
    void recordedAuthErrorMapsToErrorDataNotAnException() throws Exception {
        List<String> lines = Files.readAllLines(
                Path.of("src", "test", "resources", "fixtures", "deepseek", "chat-error-auth.response.txt"),
                StandardCharsets.UTF_8);
        assertEquals("HTTP 401", lines.get(0));
        String body = String.join("\n", lines.subList(1, lines.size()));

        AssistantMessage failure = OpenAICompletionsProvider.failure(
                FixtureStreamFn.MODEL, 401, body);

        assertEquals(StopReason.ERROR, failure.stopReason());
        assertTrue(failure.errorMessage().contains("invalid"),
                "recorded provider message must surface: " + failure.errorMessage());
    }

    @Test
    void recordedToolCallStreamAssemblesArgumentsAndStops() {
        AssistantMessageEventStream out = FixtureStreamFn.feed(
                FixtureStreamFn.MODEL, FixtureStreamFn.lines("chat-read-poem"));

        AssistantMessage message = out.result().join();
        assertEquals(StopReason.TOOL_USE, message.stopReason());
        Content.ToolCall call = message.content().stream()
                .filter(Content.ToolCall.class::isInstance)
                .map(Content.ToolCall.class::cast)
                .findFirst().orElseThrow();
        assertEquals("read", call.name());
        assertEquals("poem.txt", call.arguments().get("path"));
    }

    @Test
    void usageArrivesInTheFinishChunkAndIsFoldedIn() {
        AssistantMessageEventStream out = FixtureStreamFn.feed(
                FixtureStreamFn.MODEL, FixtureStreamFn.lines("agent-turn1"));

        AssistantMessage message = out.result().join();
        assertEquals(StopReason.TOOL_USE, message.stopReason());
        // DeepSeek folds usage into the final content chunk; the parser must not lose it
        assertTrue(message.usage().input() > 0, "prompt tokens: " + message.usage());
        assertTrue(message.usage().output() > 0, "completion tokens: " + message.usage());
    }
}
