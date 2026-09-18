package dev.jpi.tools;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import dev.jpi.agent.AgentTool;
import dev.jpi.agent.AgentToolResult;
import dev.jpi.util.CancellationToken;
import dev.jpi.ai.Content;

/** Reads a UTF-8 text file relative to the working directory. */
public final class ReadTool implements AgentTool {

    @Override
    public String name() {
        return "read";
    }

    @Override
    public String description() {
        return "Read a text file and return its contents";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of("path", Map.of("type", "string", "description", "File path")),
                "required", List.of("path"));
    }

    @Override
    public AgentToolResult execute(String toolCallId, Map<String, Object> args,
                                   CancellationToken signal, java.util.function.Consumer<Map<String, Object>> onUpdate) {
        try {
            Path path = Path.of((String) args.get("path"));
            String text = Files.readString(path, StandardCharsets.UTF_8);
            return new AgentToolResult(List.of(new Content.Text(text)), Map.of("path", path.toString()), null, false);
        } catch (Exception e) {
            throw new IllegalArgumentException("read failed: " + e.getMessage(), e);
        }
    }
}
