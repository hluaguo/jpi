package dev.jpi.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link EditTool} against real files in a temp dir. Result wording
 * and details shape are pi's edit.ts contract: "Successfully replaced N
 * block(s))", details carry diff/patch/firstChangedLine.
 */
class EditToolTest {

    @TempDir
    Path temp;

    @Test
    void appliesEditsAndReportsDiffDetails() throws Exception {
        Path file = temp.resolve("f.txt");
        Files.writeString(file, "alpha\nbeta\ngamma\n", StandardCharsets.UTF_8);

        EditTool tool = new EditTool();
        AgentToolResultShim result = AgentToolResultShim.of(tool.execute("call_1",
                Map.of("path", file.toString(),
                        "edits", List.of(Map.of("oldText", "beta", "newText", "BETA"))),
                null, null));

        assertEquals("alpha\nBETA\ngamma\n", Files.readString(file, StandardCharsets.UTF_8));
        assertEquals("Successfully replaced 1 block(s) in " + file + ".", result.text());
        assertEquals("""
                 1 alpha
                -2 beta
                +2 BETA
                 3 gamma""", result.details().get("diff"));
        assertTrue(((String) result.details().get("patch")).startsWith("--- " + file));
        assertEquals(2, result.details().get("firstChangedLine"));
    }

    @Test
    void preservesBomAndCrlfLineEndings() throws Exception {
        Path file = temp.resolve("crlf.txt");
        Files.writeString(file, "\uFEFFa\r\nb\r\n", StandardCharsets.UTF_8);

        new EditTool().execute("call_1",
                Map.of("path", file.toString(),
                        "edits", List.of(Map.of("oldText", "b", "newText", "c"))),
                null, null);

        assertEquals("\uFEFFa\r\nc\r\n", Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void missingMatchSurfacesEditDiffWording() throws Exception {
        Path file = temp.resolve("f.txt");
        Files.writeString(file, "a\nb\n", StandardCharsets.UTF_8);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new EditTool().execute("call_1",
                        Map.of("path", file.toString(),
                                "edits", List.of(Map.of("oldText", "zzz", "newText", "x"))),
                        null, null));

        assertEquals("Could not find the exact text in " + file
                + ". The old text must match exactly including all whitespace and newlines.",
                e.getMessage());
    }

    /** Minimal view of AgentToolResult for assertions; avoids coupling tests to its full shape. */
    record AgentToolResultShim(String text, Map<String, Object> details) {
        static AgentToolResultShim of(dev.jpi.agent.AgentToolResult r) {
            return new AgentToolResultShim(
                    ((dev.jpi.ai.Content.Text) r.content().get(0)).text(), r.details());
        }
    }
}
