package dev.jpi.tools;

import dev.jpi.agent.AgentToolResult;
import dev.jpi.util.CancellationToken;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Seam: {@link BashTool} process handling — output must drain concurrently with
 * the wait, or any command out-producing the OS pipe buffer dies as a false
 * timeout (pi: Node attaches data handlers at spawn, so the pipes never back up).
 */
class BashToolTest {


    @Test
    void largeOutputDoesNotDeadlockThePipeBuffers() {
        BashTool tool = new BashTool(10);
        // ~267KB of stdout: far beyond the ~64KB pipe buffer that blocks an
        // undrained child on write
        AgentToolResult result = tool.execute("call_1",
                Map.of("command", "head -c 200000 /dev/zero | base64"),
                new CancellationToken(), p -> { });
        assertEquals(0, result.details().get("exitCode"));
        assertTrue(result.content().get(0) instanceof dev.jpi.ai.Content.Text);
        assertEquals("exit code: 0", ((dev.jpi.ai.Content.Text) result.content().get(0)).text().split("\n")[0]);
    }

    @Test
    void timeoutStillReports() {
        BashTool tool = new BashTool(1);
        assertThrows(IllegalStateException.class, () -> tool.execute("call_1",
                Map.of("command", "sleep 5"), new CancellationToken(), p -> { }));
    }
}
