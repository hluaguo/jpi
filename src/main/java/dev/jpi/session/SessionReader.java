package dev.jpi.session;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;

import dev.jpi.agent.AgentEvent;
import dev.jpi.json.Json;

/**
 * Reads recorded sessions. Tolerates a <em>torn final line</em> — a crash mid-write
 * leaves an unparsable tail, and everything before it must survive; corruption
 * anywhere earlier is an error, not something to silently skip.
 *
 * <p>The envelope binds in one pass ({@code readValue} into a record): parse-tree
 * + {@code convertValue} re-traversed every line twice, which is the read-back
 * cost of a whole session. The lookahead keeps torn-tail semantics — only the
 * last line may be corrupt, so a line is only known tolerable once the next one
 * arrives.
 */
public final class SessionReader {

    /**
     * The recorder's line shape, bound in one pass. Boxed fields so a line missing
     * the envelope (valid JSON, no ts/event) is rejected as corrupt instead of
     * binding silently to defaults — pi's reader skips junk lines; jpi's stricter
     * contract keeps them distinct from a torn tail.
     */
    private record Line(Long ts, AgentEvent event) {
    }

    /** All {@code *.jsonl} files in {@code dir}, sorted by name. */
    public static List<Path> listSessions(Path dir) throws IOException {
        try (var entries = Files.list(dir)) {
            return entries
                    .filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                    .sorted()
                    .toList();
        }
    }

    public static List<SessionRecord> read(Path file) throws IOException {
        List<SessionRecord> records = new ArrayList<>();
        try (BufferedReader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String prev = in.readLine();
            if (prev == null) {
                return records;
            }
            while (true) {
                String next = in.readLine();
                addLine(records, prev, next == null, file);
                if (next == null) {
                    return records;
                }
                prev = next;
            }
        }
    }

    /**
     * Parses one line into {@code records}; a corrupt line (unparsable JSON or a
     * valid line without the ts/event envelope) is the torn tail only when it is
     * the file's last.
     */
    private static void addLine(List<SessionRecord> records, String line, boolean last, Path file)
            throws IOException {
        line = line.strip();
        if (line.isEmpty()) {
            return;
        }
        try {
            Line parsed = Json.MAPPER.readValue(line, Line.class);
            if (parsed.ts() != null && parsed.event() != null) {
                records.add(new SessionRecord(parsed.ts(), parsed.event()));
                return;
            }
        } catch (JsonProcessingException e) {
            if (!last) {
                throw corrupt(file, e);
            }
            return; // torn final line — everything before it survives
        }
        if (!last) {
            throw corrupt(file, new IOException("line without ts/event envelope"));
        }
    }

    private static IOException corrupt(Path file, Exception cause) {
        return new IOException("corrupt session line in " + file, cause);
    }

    private SessionReader() {
    }
}
