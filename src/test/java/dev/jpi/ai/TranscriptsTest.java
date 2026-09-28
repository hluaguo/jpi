package dev.jpi.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link Transcripts} text rendering. Expected strings are worked
 * examples written by hand — the renderer must reproduce them literally.
 */
class TranscriptsTest {

    @Test
    void contentTextJoinsTextBlocksAndSkipsTheRest() {
        List<Content> blocks = List.of(
                new Content.Text("line one"),
                new Content.Thinking("hidden chain of thought"),
                new Content.Image("aGk=", "image/png"),
                new Content.Text("line two"),
                new Content.ToolCall("call_1", "bash", java.util.Map.of("cmd", "ls")));

        assertEquals("line one\nline two", Transcripts.contentText(blocks));
    }

    @Test
    void contentTextWithCustomSeparator() {
        List<Content> blocks = List.of(new Content.Text("a"), new Content.Text("b"));

        assertEquals("a | b", Transcripts.contentText(blocks, " | "));
    }

    @Test
    void contentTextOfEmptyContentIsEmpty() {
        assertEquals("", Transcripts.contentText(List.of()));
        assertEquals("", Transcripts.contentText(List.of(new Content.Image("aGk=", "image/png"))));
    }

    @Test
    void rendersUserText() {
        assertEquals("[user] read demo.txt",
                Transcripts.render(new UserMessage(UserMessage.of("read demo.txt").content(), 0L)));
    }

    @Test
    void rendersAssistantThinkingTextAndToolCallAsSeparateLines() {
        AssistantMessage message = new AssistantMessage("api", "provider", "m",
                List.of(
                        new Content.Thinking("pondering"),
                        new Content.Text("Let me check."),
                        new Content.ToolCall("call_1", "read", java.util.Map.of("path", "demo.txt"))),
                null, StopReason.TOOL_USE, null, null, 0L);

        assertEquals("[thinking] pondering\n[assistant] Let me check.\n[call read] {path=demo.txt}",
                Transcripts.render(message));
    }

    @Test
    void rendersErrorAssistantAsErrorLine() {
        AssistantMessage error = new AssistantMessage("api", "provider", "m", List.of(),
                null, StopReason.ERROR, "rate limited after 3 attempts", null, 0L);

        assertEquals("[error] rate limited after 3 attempts", Transcripts.render(error));
    }

    @Test
    void rendersAWholeTranscriptWithBlankLinesBetweenMessages() {
        AssistantMessage call = new AssistantMessage("api", "provider", "m",
                List.of(new Content.ToolCall("call_1", "read", java.util.Map.of("path", "demo.txt"))),
                null, StopReason.TOOL_USE, null, null, 0L);
        ToolResultMessage result = new ToolResultMessage("call_1", "read",
                List.of(new Content.Text("jpi is a minimal Java port.")), java.util.Map.of(), false, 0L);
        AssistantMessage answer = new AssistantMessage("api", "provider", "m",
                List.of(new Content.Text("The file says: jpi is a minimal Java port.")),
                null, StopReason.STOP, null, null, 0L);

        assertEquals("""
                [user] read demo.txt and tell me what's in it
                
                [call read] {path=demo.txt}
                
                [tool read] jpi is a minimal Java port.
                
                [assistant] The file says: jpi is a minimal Java port.""",
                Transcripts.render(List.of(UserMessage.of("read demo.txt and tell me what's in it"), call, result, answer)));
    }

    @Test
    void marksErrorToolResults() {
        ToolResultMessage failure = new ToolResultMessage("call_1", "bash",
                List.of(new Content.Text("exit code 1: no such file")), java.util.Map.of(), true, 0L);

        assertEquals("[tool bash] ✗ exit code 1: no such file", Transcripts.render(failure));
    }
}
