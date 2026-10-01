package dev.jpi.ai.providers;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import dev.jpi.ai.AssistantMessageEventStream;
import dev.jpi.ai.Context;
import dev.jpi.ai.Model;
import dev.jpi.ai.StreamFn;
import dev.jpi.ai.StreamOptions;

/**
 * Replays recorded wire fixtures through the REAL adapter parse path —
 * {@code SseParser} → {@code OpenAIStreamParser} — exactly the code the live
 * provider runs on each response line, minus the socket. The suite stays offline
 * and byte-deterministic while exercising DeepSeek's real-world stream shapes
 * (per-character argument deltas, usage folded into the finish chunk, preamble
 * text before tool calls).
 *
 * <p>Fixtures live on the test classpath at {@code /fixtures/deepseek/}; the
 * standalone bench (compiled outside Maven) falls back to
 * {@code src/test/resources/fixtures/deepseek/} on disk.
 */
public final class FixtureStreamFn implements StreamFn {

    /** The model every fixture was recorded against (request id must match the served model). */
    public static final Model MODEL =
            new Model("deepseek-flash", "openai-completions", "deepseek",
                    "https://api.deepseek.com", 131072, 8192);

    private final List<String> fixtureNames;
    private int next;

    private FixtureStreamFn(List<String> fixtureNames) {
        this.fixtureNames = List.copyOf(fixtureNames);
    }

    /** A provider scripting one LLM call per named fixture, popped in order. */
    public static StreamFn scripted(String... fixtureNames) {
        return new FixtureStreamFn(List.of(fixtureNames));
    }

    /** The recorded SSE body of a fixture, as the wire delivered it: one line per element. */
    public static List<String> lines(String fixtureName) {
        try {
            return Files.readAllLines(fixturePath(fixtureName), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("fixture not found: " + fixtureName, e);
        }
    }

    private static Path fixturePath(String name) {
        var resource = FixtureStreamFn.class.getResource("/fixtures/deepseek/" + name + ".response.sse");
        if (resource != null) {
            try {
                return Path.of(resource.toURI());
            } catch (Exception e) {
                throw new IllegalStateException("cannot resolve fixture resource: " + name, e);
            }
        }
        // standalone bench runs outside Maven — resolve against the source tree
        Path dev = Path.of("src", "test", "resources", "fixtures", "deepseek", name + ".response.sse");
        if (Files.exists(dev)) {
            return dev;
        }
        throw new IllegalStateException("fixture not found: " + name);
    }

    @Override
    public synchronized AssistantMessageEventStream stream(Model model, Context ignored, StreamOptions options) {
        if (next >= fixtureNames.size()) {
            throw new IllegalStateException("no fixture scripted for call " + (next + 1));
        }
        return feed(model, lines(fixtureNames.get(next++)));
    }

    /** Feeds recorded SSE lines through the real parser chain and returns the assembled stream. */
    public static AssistantMessageEventStream feed(Model model, List<String> lines) {
        AssistantMessageEventStream out = new AssistantMessageEventStream();
        OpenAIStreamParser parser = new OpenAIStreamParser(model, out);
        SseParser sse = new SseParser(parser::sseEvent);
        for (String line : lines) {
            sse.processLine(line);
        }
        sse.end();
        parser.complete();
        return out;
    }
}
