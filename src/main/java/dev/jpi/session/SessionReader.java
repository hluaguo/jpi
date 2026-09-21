package dev.jpi.session;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.jpi.agent.AgentEvent;
import dev.jpi.json.Json;

/**
 * Reads recorded sessions. Tolerates a <em>torn final line</em> — a crash mid-write
 * leaves an unparsable tail, and everything before it must survive; corruption
 * anywhere earlier is an error, not something to silently skip.
 */
public final class SessionReader {

    /** All {@code *.jsonl} files in {@code dir}, sorted by name. */
    public static List<Path> listSessions(Path dir) throws IOException {
        try (Stream<Path> entries = Files.list(dir)) {
            return entries
                    .filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                    .sorted()
                    .toList();
        }
    }

    public static List<SessionRecord> read(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        List<SessionRecord> records = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.isEmpty()) {
                continue;
            }
            boolean tornTail = i == lines.size() - 1;
            ObjectNode parsed = parse(line);
            if (parsed == null || !isWellFormed(parsed)) {
                // structurally invalid (valid JSON, but no ts/event envelope) is the
                // same corruption a torn write leaves — pi's reader skips junk lines;
                // jpi's stricter contract still tolerates a torn final line
                if (tornTail) {
                    break;
                }
                throw new IOException("corrupt session line " + (i + 1) + " in " + file);
            }
            records.add(new SessionRecord(parsed.get("ts").asLong(),
                    Json.MAPPER.convertValue(parsed.get("event"), AgentEvent.class)));
        }
        return records;
    }

    /** A line is well-formed when it carries the recorder's envelope: a numeric ts and an event object. */
    private static boolean isWellFormed(ObjectNode parsed) {
        return parsed.hasNonNull("ts") && parsed.get("ts").isNumber()
                && parsed.has("event") && parsed.get("event").isObject();
    }

    private static ObjectNode parse(String line) {
        try {
            return (ObjectNode) Json.MAPPER.readTree(line);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private SessionReader() {
    }
}
