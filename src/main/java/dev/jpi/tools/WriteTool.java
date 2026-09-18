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

/** Writes UTF-8 text to a file relative to the working directory (creates parents). */
public final class WriteTool implements AgentTool {

    @Override
    public String name() {
        return "write";
    }

    @Override
    public String description() {
        return "Write text to a file, creating it if needed";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "path", Map.of("type", "string", "description", "File path"),
                        "content", Map.of("type", "string", "description", "Text to write")),
                "required", List.of("path", "content"));
    }

    @Override
    public AgentToolResult execute(String toolCallId, Map<String, Object> args,
                                   CancellationToken signal, java.util.function.Consumer<Map<String, Object>> onUpdate) {
        try {
            Path path = Path.of((String) args.get("path"));
            String content = (String) args.get("content");
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.writeString(path, content == null ? "" : content, StandardCharsets.UTF_8);
            return new AgentToolResult(
                    List.of(new Content.Text("wrote " + contentLength(content) + " chars to " + path)),
                    Map.of("path", path.toString()), null, false);
        } catch (Exception e) {
            throw new IllegalArgumentException("write failed: " + e.getMessage(), e);
        }
    }

    private static int contentLength(String content) {
        return content == null ? 0 : content.length();
    }
}
