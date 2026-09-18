package dev.jpi.json;

import dev.jpi.Fixtures;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Regenerates the pinned golden files from the same fixtures the tests assert
 * against. Run this only after an <em>intentional</em> wire-contract change, review
 * the diff, and commit it with the change that caused it:
 *
 * <pre>
 * mvn compile test-compile exec:java -Dexec.classpathScope=test \
 *       -Dexec.mainClass=dev.jpi.json.DumpGoldenFiles
 * </pre>
 *
 * <p>The goldens themselves are the review artifact: anything that shows up in the
 * diff is now part of the wire contract the DataForge SSE bridge streams.
 */
public final class DumpGoldenFiles {

    private static final Path GOLDEN_DIR = Path.of("src", "test", "resources", "golden");

    public static void main(String[] args) throws IOException {
        write("messages.json", Fixtures.allMessages().stream().map(Json::write).toList());
        write("assistant-events.json", JsonTest.allAssistantEvents().stream().map(Json::write).toList());
        write("agent-events.json", JsonTest.allAgentEvents().stream().map(Json::write).toList());
    }

    private static void write(String name, List<String> elements) throws IOException {
        StringBuilder json = new StringBuilder("[\n");
        for (int i = 0; i < elements.size(); i++) {
            json.append("  ").append(elements.get(i)).append(i < elements.size() - 1 ? ",\n" : "\n");
        }
        json.append("]\n");
        Files.write(GOLDEN_DIR.resolve(name), json.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("wrote " + GOLDEN_DIR.resolve(name));
    }

    private DumpGoldenFiles() {
    }
}
