package dev.jpi.examples;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import dev.jpi.agent.Agent;
import dev.jpi.agent.AgentEvent;
import dev.jpi.ai.Model;
import dev.jpi.ai.providers.AnthropicProvider;
import dev.jpi.ai.providers.ScriptedProvider;
import dev.jpi.tools.ReadTool;
import dev.jpi.tools.WriteTool;

/**
 * Zero-network demo of the agent loop: a {@link ScriptedProvider} scripts one tool
 * call and one answer, while the {@code read}/{@code write} tools execute for real.
 *
 * <p>Run with:
 * <pre>
 *   mvn -q compile exec:java -Dexec.mainClass=dev.jpi.examples.Demo
 * </pre>
 * or (offline is the default; {@code --live} needs {@code ANTHROPIC_API_KEY}):
 * <pre>
 *   java -cp target/classes dev.jpi.examples.Demo [--live]
 * </pre>
 */
public final class Demo {

    private static final Model CLAUDE = new Model(
            "claude-sonnet-4-5", "anthropic-messages", "anthropic",
            "https://api.anthropic.com", 200_000, 4096);

    public static void main(String[] args) throws Exception {
        boolean live = List.of(args).contains("--live");

        // a real file for the tools to work on
        Path demoFile = Path.of("demo.txt");
        Files.writeString(demoFile, "jpi is a minimal Java port of the pi agent core.",
                StandardCharsets.UTF_8);

        Agent agent;
        if (live && System.getenv("ANTHROPIC_API_KEY") != null) {
            agent = Agent.builder()
                    .streamFn(new AnthropicProvider(System.getenv("ANTHROPIC_API_KEY")))
                    .model(CLAUDE)
                    .systemPrompt("Answer in one short sentence.")
                    .tools(List.of(new ReadTool(), new WriteTool()))
                    .build();
        } else {
            ScriptedProvider provider = ScriptedProvider.builder()
                    .toolCall("call_1", "read", Map.of("path", demoFile.toString()))
                    .text("The file says: jpi is a minimal Java port of the pi agent core.")
                    .build();
            agent = Agent.builder()
                    .streamFn(provider)
                    .model(new Model(CLAUDE.id(), "scripted", "scripted", "demo", 8192, 4096))
                    .systemPrompt("Answer in one short sentence.")
                    .tools(List.of(new ReadTool(), new WriteTool()))
                    .build();
        }

        agent.subscribe(event -> {
            if (event instanceof AgentEvent.MessageStart start
                    && start.message() instanceof dev.jpi.ai.AssistantMessage assistant
                    && assistant.stopReason() == dev.jpi.ai.StopReason.PENDING) {
                System.out.println("[assistant streaming…]");
            } else if (event instanceof AgentEvent.ToolExecutionStart start) {
                System.out.println("[tool " + start.toolName() + " args " + start.args() + "]");
            } else if (event instanceof AgentEvent.ToolExecutionEnd end) {
                System.out.println("[tool " + end.toolName()
                        + " -> " + ((dev.jpi.ai.Content.Text) end.result().content().get(0)).text() + "]");
            } else if (event instanceof AgentEvent.MessageEnd end
                    && end.message() instanceof dev.jpi.ai.AssistantMessage assistant
                    && assistant.stopReason() != dev.jpi.ai.StopReason.PENDING) {
                assistant.content().forEach(block -> {
                    if (block instanceof dev.jpi.ai.Content.Text text) {
                        System.out.println("[assistant] " + text.text());
                    }
                });
            }
        });

        agent.prompt("read demo.txt and tell me what's in it");
        agent.waitForIdle().join();
        System.out.println("done. transcript: " + agent.messages().size() + " messages");
    }

    private Demo() {
    }
}
