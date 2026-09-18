# jpi

A minimal Java 21 port of the core of the [pi coding agent](https://github.com/badlogic/pi-mono):
the agent loop, the streaming LLM boundary, and provider adapters.

- **Pure loop**: `AgentLoop` is a pure function — `(model, context, prompts) → events + transcript`.
  All effects (LLM, tools) sit behind two small interfaces (`StreamFn`, `AgentTool`).
- **Failures are data**: provider errors arrive as assistant messages with
  `stopReason = ERROR/ABORTED`; tool throws become error tool results. The loop never
  throws across boundaries.
- **Protocol-first streaming**: every provider (Anthropic Messages, OpenAI-compatible
  chat completions, or a scripted fake) normalizes to the same `AssistantMessageEvent`
  vocabulary.
- **Hooks, not features**: permissions = `beforeToolCall`; steering/follow-up queues;
  `prepareNextTurn` for mid-run model/context swaps.

Dependencies: Jackson + JUnit 5 only. HTTP via `java.net.http` with a hand-rolled SSE parser.
The test suite is fully offline.

## Quickstart

```java
// script the LLM: one tool call, then one answer — zero network
ScriptedProvider provider = ScriptedProvider.builder()
        .toolCall("call_1", "read", Map.of("path", "demo.txt"))
        .text("The file says: hello from jpi!")
        .build();

Agent agent = Agent.builder()
        .streamFn(provider)
        .model(new Model("demo", "scripted", "scripted", "", 8192, 4096))
        .tools(List.of(new ReadTool()))
        .build();

agent.subscribe(event -> {
    if (event instanceof AgentEvent.MessageEnd end
            && end.message() instanceof AssistantMessage assistant
            && assistant.stopReason() == StopReason.STOP) {
        System.out.println(assistant.content());
    }
});

agent.prompt("read demo.txt and tell me what's in it");
```

Run the demo:

```
mvn -q compile exec:java        # scripted, offline; add --live with ANTHROPIC_API_KEY for the real API
mvn test                        # full offline suite
```

## Status

v0.1.0. See `PROMPT.md` for the build plan and `REPORT.md` for the design reference
(the studied pi architecture this port follows).
