package dev.jpi.ai.providers;

import java.util.function.BiConsumer;

/**
 * Minimal hand-rolled server-sent-events parser: feeds {@code event:}/{@code data:}
 * lines in, dispatches complete (name, data) pairs on blank lines.
 */
public final class SseParser {

    private final BiConsumer<String, String> handler;
    private String eventName;
    private final StringBuilder data = new StringBuilder();

    /** @param handler receives (event name, data payload) for every complete SSE event */
    public SseParser(BiConsumer<String, String> handler) {
        this.handler = handler;
    }

    /** Consumes one raw line (no trailing newline). */
    public void processLine(String line) {
        if (line.isEmpty()) {
            dispatch();
            return;
        }
        if (line.startsWith(":")) {
            return; // comment / keep-alive
        }
        if (line.startsWith("event:")) {
            eventName = stripPrefix(line, "event:").trim();
        } else if (line.startsWith("data:")) {
            String value = stripPrefix(line, "data:");
            if (value.startsWith(" ")) {
                value = value.substring(1); // strip exactly one leading space per the SSE spec
            }
            if (data.length() > 0) {
                data.append('\n');
            }
            data.append(value);
        }
        // other fields (id, retry, unknown) are ignored
    }

    /** Flushes a pending event at end of input. */
    public void end() {
        dispatch();
    }

    private void dispatch() {
        if (eventName == null && data.length() == 0) {
            return;
        }
        handler.accept(eventName, data.toString());
        eventName = null;
        data.setLength(0);
    }

    private static String stripPrefix(String line, String prefix) {
        return line.substring(prefix.length());
    }
}
