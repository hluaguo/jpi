package dev.jpi.session;

import dev.jpi.agent.AgentEvent;

/** One recorded line: wall-clock write time plus the event itself. */
public record SessionRecord(long ts, AgentEvent event) {
}
