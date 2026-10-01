package dev.jpi.ai.providers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import dev.jpi.ai.AssistantMessageEventStream;
import dev.jpi.ai.Content;
import dev.jpi.ai.Context;
import dev.jpi.ai.Message;
import dev.jpi.ai.Model;
import dev.jpi.ai.StopReason;
import dev.jpi.ai.Tool;
import dev.jpi.ai.UserMessage;
import dev.jpi.json.Json;
import dev.jpi.tools.BashTool;
import dev.jpi.tools.EditTool;
import dev.jpi.tools.ReadTool;
import dev.jpi.tools.WriteTool;

/**
 * Dev-time fixture recorder: makes REAL streaming calls to the DeepSeek API
 * (OpenAI-compatible) and saves the raw exchanges under
 * {@code src/test/resources/fixtures/deepseek/} — the exact request body the
 * {@link OpenAICompletionsProvider} mapping produces, and the verbatim SSE lines
 * the wire returned. The offline suite replays these bytes through the real
 * {@code SseParser} → {@code OpenAIStreamParser} path; no test ever touches the
 * network.
 *
 * <p>The agent-session fixtures are recorded adaptively: after each recorded turn
 * the model's tool calls are executed for real (jpi's own tools, scratch dir), the
 * results are appended, and the next turn is recorded — so the fixture set is a
 * genuine multi-turn tool-using session, frozen wire-verbatim.
 *
 * <p>Run by hand, never by the build: {@code java -cp <test classes> dev.jpi.ai.providers.RecordDeepSeekFixtures}.
 * The API key comes from {@code DEEPSEEK_API_KEY} / {@code DEEP_SEEK_APIKEY} or
 * {@code .auto/deepseek.key} and is never written to the fixtures.
 */
public final class RecordDeepSeekFixtures {

    static final String BASE_URL = "https://api.deepseek.com";
    static final Model MODEL =
            new Model("deepseek-flash", "openai-completions", "deepseek", BASE_URL, 131072, 8192);

    public static void main(String[] args) throws Exception {
        String key = System.getenv("DEEPSEEK_API_KEY");
        if (key == null || key.isBlank()) {
            key = System.getenv("DEEP_SEEK_APIKEY");
        }
        if (key == null || key.isBlank()) {
            Path keyFile = Path.of(".auto", "deepseek.key");
            if (Files.exists(keyFile)) {
                key = Files.readString(keyFile, StandardCharsets.UTF_8).strip();
            }
        }
        if (key == null || key.isBlank()) {
            System.err.println("no API key: set DEEPSEEK_API_KEY or write .auto/deepseek.key");
            System.exit(1);
        }

        Path dir = Path.of("src", "test", "resources", "fixtures", "deepseek");
        Files.createDirectories(dir);

        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();

        // 1. plain text stream — the baseline wire shape
        record(http, key, dir, "chat-text",
                "You are a concise assistant.",
                List.of(UserMessage.of("In exactly three short paragraphs, explain how a hash map handles collisions.")),
                List.of());

        // 2. long generation — the parse-heavy bench workload
        record(http, key, dir, "chat-long",
                "You are a senior Java developer. Output only code, no prose.",
                List.of(UserMessage.of("Write a single Java class implementing a fixed-capacity ring buffer of ints "
                        + "with javadoc on every method: put, take, size, isEmpty.")),
                List.of());

        // 3. a real multi-turn agent session: record a turn, execute the model's tool
        // calls with the REAL jpi tools against a scratch dir, record the next turn
        // with those results, until the model stops calling tools
        String agentSystem = "You are a coding agent. You MUST use the provided tools to change or inspect "
                + "files; never claim a change without calling a tool. Do exactly what the user asks, nothing more.";
        Path scratch = Files.createTempDirectory("agent-session");
        List<Message> conversation = new ArrayList<>(List.of(UserMessage.of(
                "Create notes.md containing exactly three lines: alpha, beta, gamma. Then verify the file "
                        + "contents with the bash tool. Then summarize in one sentence and do not call tools again.")));
        List<dev.jpi.agent.AgentTool> agentTools = List.of(
                new ReadTool(), new WriteTool(), new EditTool(), new BashTool(10));
        int turn = 0;
        boolean stopped = false;
        while (!stopped && turn < 6) {
            turn++;
            recordContext(http, key, dir, "agent-turn" + turn,
                    new Context(agentSystem, List.copyOf(conversation), jpiTools()));
            dev.jpi.ai.AssistantMessage assistant = replayAssistant(dir, "agent-turn" + turn);
            conversation.add(assistant);
            if (assistant.stopReason() == StopReason.STOP) {
                stopped = true;
                break;
            }
            for (Content block : assistant.content()) {
                if (block instanceof Content.ToolCall call) {
                    conversation.add(execute(agentTools, call));
                }
            }
        }
        if (!stopped) {
            System.err.println("agent session did not reach STOP within 6 turns");
            System.exit(1);
        }
        System.out.println("agent session: " + turn + " turns, scratch=" + scratch);

        // 4. edit tool call — multi-line oldText/newText argument JSON assembled
        // from many input deltas; the E2E suite replays it against a real file
        String poem = "The moon is a lantern.\nThe sea is a drum.\nThe night goes home.\n";
        Context editTurn = new Context(agentSystem,
                List.of(UserMessage.of("The file poem.txt currently contains:\n\n" + poem
                        + "\nUse the edit tool to replace the word lantern with window in it.")),
                jpiTools());
        recordContext(http, key, dir, "chat-edit", editTurn);

        // 5. multi-tool batch — two tool calls in ONE response (parallel batch in the loop)
        Context multiTool = new Context(agentSystem,
                List.of(UserMessage.of("Create a.txt containing the single word one, and create b.txt containing "
                        + "the single word two.")),
                jpiTools());
        recordContext(http, key, dir, "chat-multi-tool", multiTool);

        // 6. auth error path — a deliberately invalid key; captures the adapter's
        // non-200 response shape (status + body) for offline error-path tests
        errorExchange(http, dir, "chat-error-auth", requestOf(editTurn), "sk-invalid-key-for-error-fixture");

        Files.writeString(dir.resolve("README.md"), """
                Recorded wire fixtures. Source: dev.jpi.ai.providers.RecordDeepSeekFixtures
                (dev-time only; never run by the build). Provider: DeepSeek API, model deepseek-flash,
                OpenAI chat-completions wire format, streamed via POST /v1/chat/completions.
                Each <name>.request.json is the exact body OpenAICompletionsProvider.buildRequest produced;
                each <name>.response.sse is the verbatim response body. chat-error-auth.response.txt holds a
                non-200 status line + body. No credentials are recorded.
                """, StandardCharsets.UTF_8);

        System.out.println("done: " + dir);
    }

    /** Records one exchange built from (system, user messages, tools). */
    private static void record(HttpClient http, String key, Path dir, String name,
                               String system, List<Message> messages, List<Tool> tools) throws Exception {
        recordContext(http, key, dir, name, new Context(system, messages, tools));
    }

    /** Records one exchange for a fully hand-built context (multi-turn tool flows). */
    private static void recordContext(HttpClient http, String key, Path dir, String name,
                                      Context context) throws Exception {
        Map<String, Object> request = OpenAICompletionsProvider.buildRequest(MODEL, context);
        String body = Json.write(request);
        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(BASE_URL + "/v1/chat/completions"))
                .timeout(Duration.ofSeconds(180))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + key)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        long start = System.nanoTime();
        HttpResponse<java.util.stream.Stream<String>> response =
                http.send(httpRequest, HttpResponse.BodyHandlers.ofLines());
        List<String> lines = response.body().toList();
        long ms = (System.nanoTime() - start) / 1_000_000;

        if (response.statusCode() != 200) {
            System.err.println(name + ": HTTP " + response.statusCode());
            System.exit(1);
        }

        Files.writeString(dir.resolve(name + ".request.json"), body + "\n", StandardCharsets.UTF_8);
        Files.write(dir.resolve(name + ".response.sse"),
                (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8));

        boolean hasUsage = lines.stream().anyMatch(l -> l.contains("\"usage\""));
        boolean hasDone = lines.stream().anyMatch(l -> l.contains("[DONE]"));
        System.out.printf("%s: %d events, %d bytes, %d ms, usage=%s done=%s%n",
                name, lines.size(), Files.size(dir.resolve(name + ".response.sse")), ms, hasUsage, hasDone);
        if (!hasUsage || !hasDone) {
            System.err.println(name + ": stream missing usage chunk or [DONE] — inspect before committing");
            System.exit(1);
        }
    }

    /** Records a non-200 exchange: status line + body, no SSE expectations. */
    private static void errorExchange(HttpClient http, Path dir, String name, String body,
                                      String key) throws Exception {
        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(BASE_URL + "/v1/chat/completions"))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + key)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        Files.writeString(dir.resolve(name + ".request.json"), body + "\n", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve(name + ".response.txt"),
                "HTTP " + response.statusCode() + "\n" + response.body() + "\n", StandardCharsets.UTF_8);
        System.out.printf("%s: HTTP %d, %d bytes%n", name, response.statusCode(), response.body().length());
    }

    /** The request body for a context, for fixtures that reuse it (error path). */
    private static String requestOf(Context context) {
        return Json.write(OpenAICompletionsProvider.buildRequest(MODEL, context));
    }

    /** Executes one recorded tool call with the real jpi tool, as the agent loop would. */
    private static dev.jpi.ai.ToolResultMessage execute(List<dev.jpi.agent.AgentTool> tools,
                                                        Content.ToolCall call) {
        dev.jpi.agent.AgentTool tool = tools.stream()
                .filter(t -> t.name().equals(call.name())).findFirst().orElse(null);
        if (tool == null) {
            return new dev.jpi.ai.ToolResultMessage(call.id(), call.name(),
                    List.of(new Content.Text("Tool not found: " + call.name())), null, true, 0);
        }
        try {
            dev.jpi.agent.AgentToolResult result = tool.execute(call.id(), call.arguments(),
                    new dev.jpi.util.CancellationToken(), partial -> { });
            return new dev.jpi.ai.ToolResultMessage(call.id(), call.name(),
                    result.content(), result.details(), false, 0);
        } catch (Exception e) {
            return new dev.jpi.ai.ToolResultMessage(call.id(), call.name(),
                    List.of(new Content.Text(e.getMessage() == null ? e.toString() : e.getMessage())),
                    null, true, 0);
        }
    }

    /**
     * Re-parses a recorded response into the final assistant message, so the next
     * fixture's context carries exactly what the wire produced.
     */
    private static dev.jpi.ai.AssistantMessage replayAssistant(Path dir, String name) throws Exception {
        List<String> lines = Files.readAllLines(dir.resolve(name + ".response.sse"), StandardCharsets.UTF_8);
        AssistantMessageEventStream out = new AssistantMessageEventStream();
        OpenAIStreamParser parser = new OpenAIStreamParser(MODEL, out);
        SseParser sse = new SseParser(parser::sseEvent);
        for (String line : lines) {
            sse.processLine(line);
        }
        sse.end();
        parser.complete();
        return out.result().join();
    }

    /** The real jpi starter tools' wire schemas, as the agent would expose them. */
    private static List<Tool> jpiTools() {
        List<Tool> tools = new ArrayList<>();
        for (dev.jpi.agent.AgentTool t : List.of(new ReadTool(), new WriteTool(), new EditTool(), new BashTool())) {
            tools.add(new Tool(t.name(), t.description(), t.parameters()));
        }
        return tools;
    }

    private RecordDeepSeekFixtures() {
    }
}
