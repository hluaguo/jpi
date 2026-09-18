package dev.jpi.tools;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import dev.jpi.agent.AgentTool;
import dev.jpi.agent.AgentToolResult;
import dev.jpi.util.CancellationToken;
import dev.jpi.ai.Content;

/**
 * Runs a shell command with a timeout, capturing stdout and stderr. The command
 * runs in the working directory; output above {@value #MAX_OUTPUT_CHARS} chars is
 * truncated. Requires a POSIX shell ({@code bash}).
 */
public final class BashTool implements AgentTool {

    private static final int MAX_OUTPUT_CHARS = 20_000;

    private final long timeoutSeconds;

    public BashTool() {
        this(30);
    }

    public BashTool(long timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public String name() {
        return "bash";
    }

    @Override
    public String description() {
        return "Run a bash command and return its combined output";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of("command", Map.of("type", "string", "description", "The command to run")),
                "required", List.of("command"));
    }

    @Override
    public AgentToolResult execute(String toolCallId, Map<String, Object> args,
                                   CancellationToken signal, java.util.function.Consumer<Map<String, Object>> onUpdate) {
        String command = (String) args.get("command");
        Process process = null;
        try {
            process = new ProcessBuilder("bash", "-c", command)
                    .start();
            signal.onAbort(process::destroy);
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IllegalStateException("command timed out after " + timeoutSeconds + "s");
            }
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = process.exitValue();
            String output = truncate("exit code: " + exit
                    + (stdout.isBlank() ? "" : "\n" + stdout)
                    + (stderr.isBlank() ? "" : "\nstderr: " + stderr));
            return new AgentToolResult(List.of(new Content.Text(output)),
                    Map.of("exitCode", exit), null, false);
        } catch (Exception e) {
            throw new IllegalStateException("bash failed: " + e.getMessage(), e);
        } finally {
            if (process != null) {
                process.destroyForcibly();
            }
        }
    }

    private static String truncate(String text) {
        return text.length() <= MAX_OUTPUT_CHARS ? text : text.substring(0, MAX_OUTPUT_CHARS) + "…";
    }
}
