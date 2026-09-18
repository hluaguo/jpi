package dev.jpi.agent;

import dev.jpi.ai.AssistantMessage;
import dev.jpi.ai.Content;
import dev.jpi.ai.Model;
import dev.jpi.ai.StopReason;
import dev.jpi.ai.ThinkingLevel;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import dev.jpi.util.CancellationToken;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Seam 3: {@link Agent} — the stateful wrapper: subscription order, run lifecycle,
 * steering/follow-up queues, abort, waitForIdle.
 */
class AgentTest {

    private static final Model MODEL =
            new Model("test-model", "scripted", "scripted", "http://scripted.invalid", 8192, 4096);

    @Test
    void listenersInvokedSynchronouslyInSubscriptionOrder() {
        ScriptedProvider provider = ScriptedProvider.builder().text("hello").build();
        Agent agent = Agent.builder().streamFn(provider).model(MODEL).build();

        List<String> trace = new ArrayList<>();
        agent.subscribe(e -> trace.add("first:" + AgentLoopTest.label(e)));
        agent.subscribe(e -> trace.add("second:" + AgentLoopTest.label(e)));

        agent.prompt("hi");

        // both subscribers saw every event, first subscriber ahead of the second
        List<String> first = trace.stream().filter(t -> t.startsWith("first:")).toList();
        List<String> second = trace.stream().filter(t -> t.startsWith("second:")).toList();
        assertEquals(first.size(), second.size());
        assertTrue(first.size() > 2);
        for (int i = 0; i < first.size(); i++) {
            assertEquals(first.get(i).substring("first:".length()),
                    second.get(i).substring("second:".length()));
            assertTrue(trace.indexOf(first.get(i)) < trace.indexOf(second.get(i)));
        }
        assertEquals("first:agent_start", first.get(0));
        assertEquals("first:agent_end", first.get(first.size() - 1));
    }

    @Test
    void promptAppendsToTranscriptAndCallsLlmWithState() {
        ScriptedProvider provider = ScriptedProvider.builder().text("hello").build();
        AgentTool shout = AgentLoopTest.tool("shout", args -> AgentToolResult.text("SHOUT"));
        Agent agent = Agent.builder()
                .streamFn(provider)
                .model(MODEL)
                .systemPrompt("be loud")
                .tools(List.of(shout))
                .build();

        agent.prompt("hi");

        List<dev.jpi.ai.Message> messages = agent.messages();
        assertEquals(2, messages.size());
        assertEquals(List.of(new Content.Text("hi")), ((UserMessage) messages.get(0)).content());
        AssistantMessage assistant = (AssistantMessage) messages.get(1);
        assertEquals(StopReason.STOP, assistant.stopReason());
        assertEquals("hello", ((Content.Text) assistant.content().get(0)).text());

        assertEquals(1, provider.calls().size());
        assertEquals("be loud", provider.calls().get(0).context().systemPrompt());
        assertEquals(MODEL, provider.calls().get(0).model());
        assertEquals(1, provider.calls().get(0).context().tools().size());
        assertEquals("shout", provider.calls().get(0).context().tools().get(0).name());
        assertFalse(agent.isStreaming());
    }

    @Test
    void steerQueueInjectsOneAtATimeWhenConfigured() {
        ScriptedProvider provider = ScriptedProvider.builder()
                .toolCall("call_1", "echo", Map.of())
                .text("second")
                .text("third")
                .build();
        Agent agent = Agent.builder()
                .streamFn(provider)
                .model(MODEL)
                .tools(List.of(AgentLoopTest.tool("echo", args -> AgentToolResult.text("echoed"))))
                .steeringMode(Agent.QueueMode.ONE_AT_A_TIME)
                .build();
        agent.subscribe(e -> {
            if (e instanceof AgentEvent.ToolExecutionStart) {
                agent.steer("s1");
                agent.steer("s2");
            }
        });

        agent.prompt("go");

        // one steering message per turn: s1 injected before the 2nd LLM call, s2 before the 3rd
        assertEquals(3, provider.calls().size());
        assertEquals(1, provider.calls().get(1).context().messages().stream()
                .filter(m -> m instanceof UserMessage u && text(u).equals("s1")).count());
        assertEquals(0, provider.calls().get(1).context().messages().stream()
                .filter(m -> m instanceof UserMessage u && text(u).equals("s2")).count());
        assertEquals(1, provider.calls().get(2).context().messages().stream()
                .filter(m -> m instanceof UserMessage u && text(u).equals("s2")).count());
    }

    @Test
    void steerQueueInjectsAllWhenConfigured() {
        ScriptedProvider provider = ScriptedProvider.builder()
                .toolCall("call_1", "echo", Map.of())
                .text("second")
                .build();
        Agent agent = Agent.builder()
                .streamFn(provider)
                .model(MODEL)
                .tools(List.of(AgentLoopTest.tool("echo", args -> AgentToolResult.text("echoed"))))
                .steeringMode(Agent.QueueMode.ALL)
                .build();
        agent.subscribe(e -> {
            if (e instanceof AgentEvent.ToolExecutionStart) {
                agent.steer("s1");
                agent.steer("s2");
            }
        });

        agent.prompt("go");

        assertEquals(2, provider.calls().size());
        assertEquals(3, provider.calls().get(1).context().messages().stream()
                .filter(m -> m instanceof UserMessage).count()); // "go" + both steering messages
    }

    @Test
    void followUpKeepsTheLoopAlive() {
        ScriptedProvider provider = ScriptedProvider.builder()
                .text("one")
                .text("two")
                .build();
        Agent agent = Agent.builder()
                .streamFn(provider)
                .model(MODEL)
                .build();
        boolean[] followUpQueued = new boolean[1];
        agent.subscribe(e -> {
            if (e instanceof AgentEvent.MessageEnd end
                    && end.message() instanceof AssistantMessage a
                    && a.stopReason() == StopReason.STOP
                    && !followUpQueued[0]) {
                followUpQueued[0] = true;
                agent.followUp("more");
            }
        });

        agent.prompt("go");

        assertEquals(2, provider.calls().size());
        assertEquals(4, agent.messages().size()); // user, asst(one), follow-up, asst(two)
    }

    @Test
    void abortStopsRunAndReleasesWaiters() throws Exception {
        ScriptedProvider provider = ScriptedProvider.builder()
                .toolCall("call_1", "slow", Map.of())
                .text("never")
                .build();
        CountDownLatch started = new CountDownLatch(1);
        AgentTool slow = new AgentTool() {
            @Override
            public String name() {
                return "slow";
            }

            @Override
            public String description() {
                return "slow";
            }

            @Override
            public Map<String, Object> parameters() {
                return Map.of("type", "object");
            }

            @Override
            public AgentToolResult execute(String toolCallId, Map<String, Object> args,
                                           CancellationToken signal, Consumer<Map<String, Object>> onUpdate) {
                started.countDown();
                try {
                    Thread.sleep(600);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return AgentToolResult.text("slow finished");
            }
        };
        Agent agent = Agent.builder()
                .streamFn(provider)
                .model(MODEL)
                .tools(List.of(slow))
                .build();

        Thread runner = new Thread(() -> agent.prompt("go"));
        runner.start();
        assertTrue(started.await(5, TimeUnit.SECONDS));
        assertTrue(agent.isStreaming());

        agent.abort();
        runner.join(5000);
        assertFalse(runner.isAlive());

        assertFalse(agent.isStreaming());
        assertTrue(agent.waitForIdle().isDone());
        assertEquals(1, provider.calls().size(), "no second LLM call after abort");
    }

    @Test
    void isStreamingLifecycleAndIdleSignal() throws Exception {
        ScriptedProvider provider = ScriptedProvider.builder().text("hello").build();
        Agent agent = Agent.builder().streamFn(provider).model(MODEL).build();
        List<Boolean> seen = new ArrayList<>();
        agent.subscribe(e -> seen.add(agent.isStreaming()));

        assertFalse(agent.isStreaming());
        agent.prompt("hi");
        assertFalse(seen.isEmpty());
        assertTrue(seen.stream().allMatch(Boolean::booleanValue), "streaming for every event of the run");
        assertFalse(agent.isStreaming());
        assertTrue(agent.waitForIdle().isDone());
    }

    @Test
    void clearSteeringQueueDropsQueuedSteeringMessages() {
        ScriptedProvider provider = ScriptedProvider.builder()
                .toolCall("call_1", "echo", Map.of())
                .text("second")
                .build();
        Agent agent = Agent.builder()
                .streamFn(provider)
                .model(MODEL)
                .tools(List.of(AgentLoopTest.tool("echo", args -> AgentToolResult.text("echoed"))))
                .build();
        agent.subscribe(e -> {
            if (e instanceof AgentEvent.ToolExecutionStart) {
                agent.steer("s1");
                agent.steer("s2");
                assertTrue(agent.hasQueuedMessages());
                agent.clearSteeringQueue();
                assertFalse(agent.hasQueuedMessages());
            }
        });

        agent.prompt("go");

        // nothing was injected: every LLM call saw exactly the transcript so far
        assertEquals(2, provider.calls().size());
        assertEquals(1, provider.calls().get(1).context().messages().stream()
                .filter(m -> m instanceof UserMessage).count()); // just "go"
    }

    @Test
    void followUpModeOneAtATimeInjectsOneFollowUpPerWouldStop() {
        ScriptedProvider provider = ScriptedProvider.builder()
                .text("one")
                .text("two")
                .text("three")
                .build();
        Agent agent = Agent.builder()
                .streamFn(provider)
                .model(MODEL)
                .followUpMode(Agent.QueueMode.ONE_AT_A_TIME)
                .build();
        boolean[] queued = new boolean[1];
        agent.subscribe(e -> {
            if (e instanceof AgentEvent.MessageEnd end
                    && end.message() instanceof AssistantMessage a
                    && a.stopReason() == StopReason.STOP
                    && !queued[0]) {
                queued[0] = true;
                agent.followUp("f1");
                agent.followUp("f2");
            }
        });

        agent.prompt("go");

        // one follow-up per would-stop: f1 before call 2, f2 before call 3
        assertEquals(3, provider.calls().size());
        assertEquals(1, provider.calls().get(1).context().messages().stream()
                .filter(m -> m instanceof UserMessage u && text(u).equals("f1")).count());
        assertEquals(1, provider.calls().get(2).context().messages().stream()
                .filter(m -> m instanceof UserMessage u && text(u).equals("f2")).count());
        assertFalse(agent.hasQueuedMessages());
    }

    @Test
    void followUpModeAllInjectsEverythingAtOnce() {
        ScriptedProvider provider = ScriptedProvider.builder()
                .text("one")
                .text("two")
                .build();
        Agent agent = Agent.builder()
                .streamFn(provider)
                .model(MODEL)
                .followUpMode(Agent.QueueMode.ALL)
                .build();
        boolean[] queued = new boolean[1];
        agent.subscribe(e -> {
            if (e instanceof AgentEvent.MessageEnd end
                    && end.message() instanceof AssistantMessage a
                    && a.stopReason() == StopReason.STOP
                    && !queued[0]) {
                queued[0] = true;
                agent.followUp("f1");
                agent.followUp("f2");
            }
        });

        agent.prompt("go");

        // both follow-ups ride in a single turn
        assertEquals(2, provider.calls().size());
        assertEquals(3, provider.calls().get(1).context().messages().stream()
                .filter(m -> m instanceof UserMessage).count()); // "go" + f1 + f2
    }

    @Test
    void clearAllQueuesEmptiesBothQueues() {
        Agent agent = Agent.builder()
                .streamFn(ScriptedProvider.builder().text("x").build())
                .model(MODEL)
                .build();
        agent.steer("s");
        agent.followUp("f");
        assertTrue(agent.hasQueuedMessages());

        agent.clearAllQueues();

        assertFalse(agent.hasQueuedMessages());
    }

    @Test
    void resetRejectsWhileStreaming() throws Exception {
        ScriptedProvider provider = ScriptedProvider.builder()
                .toolCall("call_1", "slow", Map.of())
                .text("done")
                .build();
        CountDownLatch started = new CountDownLatch(1);
        AgentTool slow = AgentLoopTest.tool("slow", args -> {
            started.countDown();
            try {
                Thread.sleep(600);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return AgentToolResult.text("ok");
        });
        Agent agent = Agent.builder().streamFn(provider).model(MODEL).tools(List.of(slow)).build();

        Thread runner = new Thread(() -> agent.prompt("go"));
        runner.start();
        assertTrue(started.await(5, TimeUnit.SECONDS));

        IllegalStateException thrown = assertThrows(IllegalStateException.class, agent::reset);
        assertTrue(thrown.getMessage().contains("streaming"));

        agent.abort();
        runner.join(5000);
    }

    @Test
    void resetClearsTranscriptAndQueuesForANewRun() {
        ScriptedProvider provider = ScriptedProvider.builder()
                .text("first")
                .text("fresh")
                .build();
        Agent agent = Agent.builder().streamFn(provider).model(MODEL).build();
        agent.prompt("go");
        agent.steer("leftover");
        agent.followUp("also leftover");

        agent.reset();

        assertTrue(agent.messages().isEmpty());
        assertFalse(agent.hasQueuedMessages());

        agent.prompt("new chat");
        assertEquals(2, provider.calls().size());
        // the new run starts from a clean transcript
        assertEquals(1, provider.calls().get(1).context().messages().size());
        assertEquals(2, agent.messages().size());
    }

    @Test
    void continueRunRejectsEmptyOrAssistantTailedTranscripts() {
        ScriptedProvider provider = ScriptedProvider.builder().text("x").build();
        Agent agent = Agent.builder().streamFn(provider).model(MODEL).build();

        IllegalStateException empty = assertThrows(IllegalStateException.class, agent::continueRun);
        assertTrue(empty.getMessage().contains("no messages"));

        agent.prompt("hi");

        // a completed run ends with an assistant message — retry from there is a
        // provider rejection in waiting; the loop must refuse up front
        IllegalStateException assistant = assertThrows(IllegalStateException.class, agent::continueRun);
        assertTrue(assistant.getMessage().contains("assistant"));
        assertEquals(1, provider.calls().size());
    }

    @Test
    void continueRunResumesFromAnAbortedToolBatch() throws Exception {
        ScriptedProvider provider = ScriptedProvider.builder()
                .toolCall("call_1", "slow", Map.of())
                .text("resumed")
                .build();
        CountDownLatch started = new CountDownLatch(1);
        AgentTool slow = new AgentTool() {
            @Override
            public String name() {
                return "slow";
            }

            @Override
            public String description() {
                return "slow";
            }

            @Override
            public Map<String, Object> parameters() {
                return Map.of("type", "object");
            }

            @Override
            public AgentToolResult execute(String toolCallId, Map<String, Object> args,
                                           CancellationToken signal, Consumer<Map<String, Object>> onUpdate) {
                started.countDown();
                // cooperative cancellation: poll the signal instead of a blind sleep
                long deadline = System.currentTimeMillis() + 5_000;
                while (!signal.isAborted() && System.currentTimeMillis() < deadline) {
                    try {
                        Thread.sleep(25);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                return AgentToolResult.text("ok");
            }
        };
        Agent agent = Agent.builder().streamFn(provider).model(MODEL).tools(List.of(slow)).build();

        Thread runner = new Thread(() -> agent.prompt("go"));
        runner.start();
        assertTrue(started.await(5, TimeUnit.SECONDS));
        agent.abort();
        runner.join(5_000);
        assertFalse(runner.isAlive());

        // transcript ends with a tool result: exactly pi's retry case
        Object last = agent.messages().get(agent.messages().size() - 1);
        assertTrue(last instanceof ToolResultMessage);

        agent.continueRun();

        assertEquals(2, provider.calls().size());
        AssistantMessage resumed = (AssistantMessage) agent.messages().get(agent.messages().size() - 1);
        assertEquals("resumed", ((Content.Text) resumed.content().get(0)).text());
    }

    @Test
    void promptWhileStreamingIsRejected() throws Exception {
        ScriptedProvider provider = ScriptedProvider.builder()
                .toolCall("call_1", "slow", Map.of())
                .text("done")
                .build();
        CountDownLatch started = new CountDownLatch(1);
        AgentTool slow = AgentLoopTest.tool("slow", args -> {
            started.countDown();
            try {
                Thread.sleep(600);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return AgentToolResult.text("ok");
        });
        Agent agent = Agent.builder().streamFn(provider).model(MODEL).tools(List.of(slow)).build();

        Thread runner = new Thread(() -> agent.prompt("go"));
        runner.start();
        assertTrue(started.await(5, TimeUnit.SECONDS));
        assertThrows(IllegalStateException.class, () -> agent.prompt("again"));
        agent.abort();
        runner.join(5000);
    }

    @Test
    void agentWiresTransformContextIntoTheLoop() {
        ScriptedProvider provider = ScriptedProvider.builder().text("hello").text("hello again").build();
        Agent agent = Agent.builder()
                .streamFn(provider)
                .model(MODEL)
                .loopConfig(AgentLoopConfig.builder()
                        .transformContext((model, messages) -> List.of(messages.get(messages.size() - 1)))
                        .build())
                .build();

        agent.prompt("keep");
        agent.prompt("only this is sent");

        // the second call's provider view kept only the latest message
        assertEquals(2, provider.calls().size());
        assertEquals(1, provider.calls().get(1).context().messages().size());
    }

    private static String text(UserMessage message) {
        return ((Content.Text) message.content().get(0)).text();
    }
}
