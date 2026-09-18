package dev.jpi.session;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import dev.jpi.agent.AgentEvent;
import dev.jpi.json.Json;

/**
 * Appends every {@link AgentEvent} of a run to a versioned JSONL file — one line per
 * event, flushed per line, so a crash loses at most the event being written. A
 * session doubles as the transcript (it is a consumer of the same events a UI sees)
 * and, via {@link SessionReplayer}, as a zero-network replay source.
 */
public final class SessionRecorder implements Consumer<AgentEvent>, AutoCloseable {

    private final BufferedWriter out;
    private final LongSupplier clock;

    public SessionRecorder(Path file) throws IOException {
        this(file, System::currentTimeMillis);
    }

    public SessionRecorder(Path file, LongSupplier clock) throws IOException {
        this.out = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        this.clock = clock;
    }

    @Override
    public void accept(AgentEvent event) {
        try {
            com.fasterxml.jackson.databind.node.ObjectNode line = Json.MAPPER.createObjectNode();
            line.put("v", 1);
            line.put("ts", clock.getAsLong());
            line.set("event", Json.MAPPER.valueToTree(event));
            out.write(Json.MAPPER.writeValueAsString(line));
            out.newLine();
            out.flush();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("failed to record session event", e);
        }
    }

    @Override
    public void close() throws IOException {
        out.close();
    }
}
