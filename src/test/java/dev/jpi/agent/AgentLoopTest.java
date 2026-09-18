package dev.jpi.agent;

import dev.jpi.ai.AssistantMessage;
import dev.jpi.ai.AssistantMessageEvent;
import dev.jpi.ai.Content;
import dev.jpi.ai.Context;
import dev.jpi.ai.Message;
import dev.jpi.ai.Model;
import dev.jpi.ai.StopReason;
import dev.jpi.ai.ToolResultMessage;
import dev.jpi.ai.UserMessage;
import dev.jpi.ai.providers.ScriptedProvider;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import dev.jpi.util.CancellationToken;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Seam 2: {@link AgentLoop} — driven by a {@link ScriptedProvider}, asserting the
 * exact event protocol and transcript for each red→green slice.
 */
class AgentLoopTest {

    private static final Model MODEL =
            new Model("test-model", "scripted", "scripted", "http://scripted.invalid", 8192, 4096);

    @Test
    void textOnlyTurnEmitsExactEventSequenceAndTranscript() {
        ScriptedProvider provider = ScriptedProvider.builder().text("Hello world").build();
        AgentLoop loop = new AgentLoop(provider);

        List<AgentEvent> agentEvents = new ArrayList<>();
        AgentLoop.LoopResult result = loop.run(
                MODEL,
                new AgentContext("be nice", List.of(), List.of()),
                List.of(UserMessage.of("hi")),
                agentEvents::add);

        assertEquals(
                List.of("agent_start", "turn_start",
                        "message_start", "message_end",
                        "message_start", "message_update", "message_update", "message_update", "message_end",
                        "turn_end", "agent_end"),
                labels(agentEvents));

        List<Message> messages = result.messages();
        assertEquals(2, messages.size());
        assertEquals(List.of(new Content.Text("hi")), ((UserMessage) messages.get(0)).content());

        AssistantMessage assistant = (AssistantMessage) messages.get(1);
        assertEquals(StopReason.STOP, assistant.stopReason());
        assertEquals(List.of(new Content.Text("Hello world")), assistant.content());

        // the LLM saw the system prompt and the injected user message
        assertEquals(1, provider.calls().size());
        Context llmContext = provider.calls().get(0).context();
        assertEquals("be nice", llmContext.systemPrompt());
        assertEquals(1, llmContext.messages().size());
    }

    @Test
    void toolCallTurnExecutesToolAndCallsLlmAgain() {
        ScriptedProvider provider = ScriptedProvider.builder()
                .toolCall("call_1", "read", Map.of("path", "a.txt"))
                .text("done")
                .build();
        AgentTool read = new AgentTool() {
            @Override
            public String name() {
                return "read";
            }

            @Override
            public String description() {
                return "read a file";
            }

            @Override
            public Map<String, Object> parameters() {
                return Map.of("type", "object");
            }

            @Override
            public AgentToolResult execute(String toolCallId, Map<String, Object> args,
                                           CancellationToken signal, Consumer<Map<String, Object>> onUpdate) {
                return AgentToolResult.text("file contents of " + args.get("path"));
            }
        };
        AgentLoop loop = new AgentLoop(provider);

        List<AgentEvent> events = new ArrayList<>();
        AgentLoop.LoopResult result = loop.run(
                MODEL,
                new AgentContext(null, List.of(), List.of(read)),
                List.of(UserMessage.of("read it")),
                events::add);

        assertEquals(
                List.of("agent_start", "turn_start",
                        "message_start", "message_end",
                        "message_start", "message_update", "message_update", "message_update", "message_end",
                        "tool_execution_start", "tool_execution_end",
                        "message_start", "message_end",
                        "turn_end",
                        "turn_start",
                        "message_start", "message_update", "message_update", "message_update", "message_end",
                        "turn_end", "agent_end"),
                labels(events));

        // transcript: user, assistant(toolCall), toolResult, assistant("done")
        List<Message> messages = result.messages();
        assertEquals(4, messages.size());
        ToolResultMessage toolResult = (ToolResultMessage) messages.get(2);
        assertEquals("call_1", toolResult.toolCallId());
        assertEquals("read", toolResult.toolName());
        assertEquals(List.of(new Content.Text("file contents of a.txt")), toolResult.content());
        assertFalse(toolResult.isError());
        assertEquals(StopReason.TOOL_USE, ((AssistantMessage) messages.get(1)).stopReason());

        // second LLM call saw the tool result
        assertEquals(2, provider.calls().size());
        assertEquals(3, provider.calls().get(1).context().messages().size());
    }

    @Test
    void lengthStopFailsAllToolCallsAsTruncatedWithoutExecuting() {
        ScriptedProvider provider = ScriptedProvider.builder()
                .truncatedToolCall("call_1", "read", Map.of("path", "a.txt"))
                .text("retry ok")
                .build();
        List<String> executed = new ArrayList<>();
        AgentTool read = tool("read", args -> {
            executed.add("executed");
            return AgentToolResult.text("nope");
        });
        AgentLoop loop = new AgentLoop(provider);

        List<AgentEvent> events = new ArrayList<>();
        AgentLoop.LoopResult result = loop.run(
                MODEL,
                new AgentContext(null, List.of(), List.of(read)),
                List.of(UserMessage.of("go")),
                events::add);

        assertTrue(executed.isEmpty(), "no tool may execute after a length stop");
        assertFalse(labels(events).contains("tool_execution_start"));

        ToolResultMessage toolResult = (ToolResultMessage) result.messages().get(2);
        assertTrue(toolResult.isError());
        assertEquals("Error: tool call arguments may be truncated (stop_reason: length)",
                ((Content.Text) toolResult.content().get(0)).text());

        // the loop retries: second LLM call happened, loop ended cleanly after text
        assertEquals(2, provider.calls().size());
        assertEquals(StopReason.STOP, result.stopReason());
    }

    @Test
    void beforeToolCallBlockProducesErrorResultWithoutExecuting() {
        ScriptedProvider provider = ScriptedProvider.builder()
                .toolCall("call_1", "read", Map.of("path", "a.txt"))
                .text("gave up")
                .build();
        List<String> executed = new ArrayList<>();
        AgentTool read = tool("read", args -> {
            executed.add("executed");
            return AgentToolResult.text("nope");
        });
        AgentLoopConfig config = AgentLoopConfig.builder()
                .beforeToolCall((toolCallTool, call) -> AgentLoopConfig.BeforeToolCallResult.block("permission denied"))
                .build();
        AgentLoop loop = new AgentLoop(provider, config);

        List<AgentEvent> events = new ArrayList<>();
        AgentLoop.LoopResult result = loop.run(
                MODEL,
                new AgentContext(null, List.of(), List.of(read)),
                List.of(UserMessage.of("go")),
                events::add);

        assertTrue(executed.isEmpty(), "execute must never run when blocked");
        assertFalse(labels(events).contains("tool_execution_start"));
        ToolResultMessage toolResult = (ToolResultMessage) result.messages().get(2);
        assertTrue(toolResult.isError());
        assertEquals("Error: permission denied", ((Content.Text) toolResult.content().get(0)).text());
        assertEquals(StopReason.STOP, result.stopReason());
    }

    @Test
    void afterToolCallHookOverridesResult() {
        ScriptedProvider provider = ScriptedProvider.builder()
                .toolCall("call_1", "read", Map.of("path", "a.txt"))
                .text("done")
                .build();
        AgentLoopConfig config = AgentLoopConfig.builder()
                .afterToolCall((tool, call, resultMessage) -> new ToolResultMessage(
                        resultMessage.toolCallId(), resultMessage.toolName(),
                        List.of(new Content.Text("overridden")), resultMessage.details(),
                        true, resultMessage.timestamp()))
                .build();
        AgentLoop loop = new AgentLoop(provider, config);

        List<AgentEvent> events = new ArrayList<>();
        AgentLoop.LoopResult result = loop.run(
                MODEL,
                new AgentContext(null, List.of(), List.of(tool("read", args -> AgentToolResult.text("raw")))),
                List.of(UserMessage.of("go")),
                events::add);

        ToolResultMessage toolResult = (ToolResultMessage) result.messages().get(2);
        assertTrue(toolResult.isError());
        assertEquals(List.of(new Content.Text("overridden")), toolResult.content());

        // tool_execution_end carries the overridden result too
        AgentEvent.ToolExecutionEnd end = events.stream()
                .filter(AgentEvent.ToolExecutionEnd.class::isInstance)
                .map(AgentEvent.ToolExecutionEnd.class::cast)
                .findFirst().orElseThrow();
        assertEquals(List.of(new Content.Text("overridden")), end.result().content());
    }

    @Test
    void abortMidToolYieldsAbortedResultsAndEndsRun() throws Exception {
        ScriptedProvider provider = ScriptedProvider.builder()
                .toolCalls(List.of(
                        new Content.ToolCall("call_1", "slow", Map.of()),
                        new Content.ToolCall("call_2", "slow", Map.of())))
                .build();
        CountDownLatch toolStarted = new CountDownLatch(1);
        CountDownLatch abortRequested = new CountDownLatch(1);
        AgentTool slow = new AgentTool() {
            @Override
            public String name() {
                return "slow";
            }

            @Override
            public String description() {
                return "slow test tool";
            }

            @Override
            public Map<String, Object> parameters() {
                return Map.of("type", "object");
            }

            @Override
            public AgentToolResult execute(String toolCallId, Map<String, Object> args,
                                           CancellationToken signal, Consumer<Map<String, Object>> onUpdate) {
                toolStarted.countDown();
                try {
                    abortRequested.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return AgentToolResult.text("cancelled work");
            }
        };
        AgentLoop loop = new AgentLoop(provider);
        CancellationToken signal = new CancellationToken();
        signal.onAbort(abortRequested::countDown);

        List<AgentEvent> events = new ArrayList<>();
        java.util.concurrent.atomic.AtomicReference<AgentLoop.LoopResult> resultRef = new java.util.concurrent.atomic.AtomicReference<>();
        Thread runner = new Thread(() -> resultRef.set(loop.run(
                MODEL,
                new AgentContext(null, List.of(), List.of(slow)),
                List.of(UserMessage.of("go")),
                signal, events::add)));
        runner.start();
        assertTrue(toolStarted.await(5, TimeUnit.SECONDS));
        signal.abort();
        runner.join(5000);
        assertFalse(runner.isAlive(), "run must finish after abort");

        AgentLoop.LoopResult result = resultRef.get();
        assertEquals(StopReason.ABORTED, result.stopReason());
        assertEquals(1, provider.calls().size(), "no further LLM call after abort");

        // transcript: user, assistant(2 toolCalls), toolResult(ran, cancelled), toolResult(never ran, aborted)
        List<Message> messages = result.messages();
        assertEquals(4, messages.size());
        ToolResultMessage first = (ToolResultMessage) messages.get(2);
        assertFalse(first.isError());
        assertEquals("cancelled work", ((Content.Text) first.content().get(0)).text());
        ToolResultMessage second = (ToolResultMessage) messages.get(3);
        assertTrue(second.isError());
        assertEquals("Error: Operation aborted", ((Content.Text) second.content().get(0)).text());
        assertEquals("call_2", second.toolCallId());
    }

    @Test
    void steeringInjectedBetweenTurnsAndFollowUpKeepsLoopAlive() {
        ScriptedProvider provider = ScriptedProvider.builder()
                .toolCall("call_1", "echo", Map.of())
                .text("second")
                .text("third")
                .build();
        List<Message> steering = new ArrayList<>(List.of(UserMessage.of("steer")));
        List<Message> followUps = new ArrayList<>(List.of(UserMessage.of("follow up")));
        java.util.concurrent.atomic.AtomicBoolean firstSteeringPull = new java.util.concurrent.atomic.AtomicBoolean(true);
        AgentLoopConfig config = AgentLoopConfig.builder()
                .getSteeringMessages(() -> firstSteeringPull.compareAndSet(true, false)
                        ? List.of()
                        : (steering.isEmpty() ? List.of() : List.of(steering.remove(0))))
                .getFollowUpMessages(() -> followUps.isEmpty() ? List.of() : List.of(followUps.remove(0)))
                .build();
        AgentLoop loop = new AgentLoop(provider, config);

        List<AgentEvent> events = new ArrayList<>();
        AgentLoop.LoopResult result = loop.run(
                MODEL,
                new AgentContext(null, List.of(), List.of(tool("echo", args -> AgentToolResult.text("echoed")))),
                List.of(UserMessage.of("go")),
                events::add);

        assertEquals(3, provider.calls().size(), "steering + follow-up must extend the run");
        List<Message> messages = result.messages();
        assertEquals(7, messages.size());
        assertEquals("steer", ((Content.Text) ((UserMessage) messages.get(3)).content().get(0)).text());
        assertEquals(StopReason.STOP, ((AssistantMessage) messages.get(4)).stopReason());
        assertEquals("follow up", ((Content.Text) ((UserMessage) messages.get(5)).content().get(0)).text());
        assertEquals(StopReason.STOP, ((AssistantMessage) messages.get(6)).stopReason());
        assertEquals(StopReason.STOP, result.stopReason());
    }

    @Test
    void shouldStopAfterTurnEndsRunEarly() {
        ScriptedProvider provider = ScriptedProvider.builder()
                .toolCall("call_1", "echo", Map.of())
                .text("never reached")
                .build();
        AgentLoopConfig config = AgentLoopConfig.builder()
                .shouldStopAfterTurn((assistant, toolResults, turnIndex) -> true)
                .build();
        AgentLoop loop = new AgentLoop(provider, config);

        AgentLoop.LoopResult result = loop.run(
                MODEL,
                new AgentContext(null, List.of(), List.of(tool("echo", args -> AgentToolResult.text("echoed")))),
                List.of(UserMessage.of("go")));

        assertEquals(1, provider.calls().size(), "run must stop after the first turn");
        assertEquals(StopReason.TOOL_USE, result.stopReason());
    }

    @Test
    void prepareNextTurnCanSwapModelAndContext() {
        ScriptedProvider provider = ScriptedProvider.builder()
                .toolCall("call_1", "echo", Map.of())
                .text("after swap")
                .build();
        Model otherModel = new Model("other-model", "scripted", "scripted", "http://scripted.invalid", 8192, 4096);
        AgentLoopConfig config = AgentLoopConfig.builder()
                .prepareNextTurn((model, ctx, lastTurn) -> new AgentLoopConfig.TurnPlan(
                        otherModel, new AgentContext("new system", ctx.messages(), ctx.tools())))
                .build();
        AgentLoop loop = new AgentLoop(provider, config);

        AgentLoop.LoopResult result = loop.run(
                MODEL,
                new AgentContext("old system", List.of(), List.of(tool("echo", args -> AgentToolResult.text("echoed")))),
                List.of(UserMessage.of("go")));

        assertEquals(2, provider.calls().size());
        assertEquals("test-model", provider.calls().get(0).model().id());
        assertEquals("old system", provider.calls().get(0).context().systemPrompt());
        assertEquals("other-model", provider.calls().get(1).model().id());
        assertEquals("new system", provider.calls().get(1).context().systemPrompt());
        // the transcript carried over through the swap
        assertEquals(3, provider.calls().get(1).context().messages().size());
        assertEquals(StopReason.STOP, result.stopReason());
    }

    @Test
    void parallelToolExecutionEndsInCompletionOrderButMessagesStayInSourceOrder() throws Exception {
        ScriptedProvider provider = ScriptedProvider.builder()
                .toolCalls(List.of(
                        new Content.ToolCall("call_1", "slow", Map.of()),
                        new Content.ToolCall("call_2", "fast", Map.of())))
                .text("all done")
                .build();
        CountDownLatch fastEndEmitted = new CountDownLatch(1);
        AgentTool slow = tool("slow", args -> {
            try {
                fastEndEmitted.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return AgentToolResult.text("slow done");
        });
        AgentTool fast = tool("fast", args -> AgentToolResult.text("fast done"));
        AgentLoopConfig config = AgentLoopConfig.builder()
                .toolExecution(AgentLoopConfig.ToolExecution.PARALLEL)
                .build();
        AgentLoop loop = new AgentLoop(provider, config);

        List<AgentEvent> events = new ArrayList<>();
        Consumer<AgentEvent> listener = event -> {
            events.add(event);
            if (event instanceof AgentEvent.ToolExecutionEnd end && end.toolCallId().equals("call_2")) {
                fastEndEmitted.countDown();
            }
        };
        AgentLoop.LoopResult result = loop.run(
                MODEL,
                new AgentContext(null, List.of(), List.of(slow, fast)),
                List.of(UserMessage.of("go")),
                listener);

        // ends in completion order: fast first
        List<String> endIds = events.stream()
                .filter(AgentEvent.ToolExecutionEnd.class::isInstance)
                .map(AgentEvent.ToolExecutionEnd.class::cast)
                .map(AgentEvent.ToolExecutionEnd::toolCallId)
                .toList();
        assertEquals(List.of("call_2", "call_1"), endIds);

        // toolResult messages in assistant source order: slow first
        List<String> resultIds = result.messages().stream()
                .filter(ToolResultMessage.class::isInstance)
                .map(ToolResultMessage.class::cast)
                .map(ToolResultMessage::toolCallId)
                .toList();
        assertEquals(List.of("call_1", "call_2"), resultIds);
        assertEquals(StopReason.STOP, result.stopReason());
    }

    /** Compact tool factory: named tool whose execution is delegated to {@code fn}. */
    static AgentTool tool(String name, java.util.function.Function<Map<String, Object>, AgentToolResult> fn) {
        return new AgentTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "test tool " + name;
            }

            @Override
            public Map<String, Object> parameters() {
                return Map.of("type", "object");
            }

            @Override
            public AgentToolResult execute(String toolCallId, Map<String, Object> args,
                                           CancellationToken signal, Consumer<Map<String, Object>> onUpdate) {
                return fn.apply(args);
            }
        };
    }

    static List<String> labels(List<AgentEvent> events) {
        return events.stream().map(AgentLoopTest::label).toList();
    }

    static String label(AgentEvent event) {
        if (event instanceof AgentEvent.Start) return "agent_start";
        if (event instanceof AgentEvent.TurnStart) return "turn_start";
        if (event instanceof AgentEvent.MessageStart) return "message_start";
        if (event instanceof AgentEvent.MessageUpdate) return "message_update";
        if (event instanceof AgentEvent.MessageEnd) return "message_end";
        if (event instanceof AgentEvent.ToolExecutionStart) return "tool_execution_start";
        if (event instanceof AgentEvent.ToolExecutionUpdate) return "tool_execution_update";
        if (event instanceof AgentEvent.ToolExecutionEnd) return "tool_execution_end";
        if (event instanceof AgentEvent.TurnEnd) return "turn_end";
        if (event instanceof AgentEvent.End) return "agent_end";
        throw new AssertionError("unknown event: " + event);
    }
}
