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
 *
 * <p>Like any listener, IO failures here surface as {@link java.io.UncheckedIOException}
 * on the thread driving the run — recording is best-effort by construction, and the
 * caller decides whether a full disk should end the run.
 */
public final class SessionRecorder implements Consumer<AgentEvent>, AutoCloseable {

    /**
     * Streams events without closing the shared writer: Jackson's default
     * {@code AUTO_CLOSE_TARGET} closed the session file after the first event.
     * ObjectWriter is immutable and thread-safe, so one instance serves all runs.
     */
    private static final com.fasterxml.jackson.databind.ObjectWriter EVENT_WRITER =
            Json.MAPPER.writer()
                    .without(com.fasterxml.jackson.core.JsonGenerator.Feature.AUTO_CLOSE_TARGET);

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
            // the envelope is written raw and the event streams through Jackson's
            // generator straight into the writer: building an intermediate ObjectNode
            // tree per event (valueToTree + writeValueAsString) cost ~3 allocation
            // chains for 130KB of JSONL in a 231-event session — the dominant
            // recording cost. Same JSON bytes: identical mapper config, same fields.
            //
            // The whole envelope must be one critical section: the parallel tool
            // batch emits ends from pool threads, and interleaved writes would tear
            // lines (BufferedWriter only makes each individual write atomic).
            synchronized (this) {
                out.write("{\"v\":1,\"ts\":");
                out.write(Long.toString(clock.getAsLong()));
                out.write(",\"event\":");
                EVENT_WRITER.writeValue(out, event);
                out.write("}\n");
                out.flush();
            }
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("failed to record session event", e);
        }
    }

    @Override
    public void close() throws IOException {
        out.close();
    }
}
