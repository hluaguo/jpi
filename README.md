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

## JSON wire contract (`dev.jpi.json`)

`Json.MAPPER` serializes/deserializes `Message`s, content blocks and `AgentEvent`s
with a `type` discriminator per sealed hierarchy (pi's RPC vocabulary:
`user`/`assistant`/`toolResult`, `text_delta`, `message_start`, …). Unknown fields
are tolerated on read; the exact format is pinned by golden files under
`src/test/resources/golden/` (regenerate intentionally via `DumpGoldenFiles`).
This is the payload an SSE/RPC bridge streams.

## Provider resilience (`dev.jpi.ai`)

Wrap any `StreamFn` in `RetryingStreamFn` for retries with exponential backoff and
jitter. Failures are classified once, at the adapter boundary, into `ErrorKind`
(`AUTH`, `RATE_LIMIT`, `SERVER`, `NETWORK`, `CONTEXT_OVERFLOW`, `UNKNOWN`) and
carried on the error `AssistantMessage`'s `diagnostics` field; only transient kinds
are retried, `AUTH`/overflow/abort never are. Backoff waits poll the
`CancellationToken` on `StreamOptions`, and a backoff past `maxRetryDelayMs` fails
fast. Failed attempts are buffered, so a consumer only ever sees the final attempt.

## Costs & run stats (`dev.jpi.ai` + `dev.jpi.agent`)

Token counts are provider-reported (`Usage`) and treated as authoritative — jpi
never estimates. `Model.Cost` publishes per-million rates and `CostCalculator`
joins the two. `RunStatsCollector` subscribes to a run's `AgentEvent`s and reduces
them into `RunStats`: tokens by kind, cache hits, cost, duration, message/tool
counts.

## Sessions (`dev.jpi.session`)

`SessionRecorder` appends every `AgentEvent` to a versioned JSONL file (one
`{"v":1,"ts":…,"event":…}` line per event, flushed per line). `SessionReader`
lists sessions and tolerates a torn final line (crash mid-write). `SessionReplayer`
rebuilds the transcript, feeds events verbatim to a UI, and turns recorded
assistant responses into a `ScriptedProvider` — replay a recorded conversation
with zero network.

## Context guard (`dev.jpi.agent`)

`AgentLoopConfig.transformContext` is the compaction hook: it rewrites the
provider-bound message list before every call; the transcript stays intact.
`DeterministicPruner` triggers when the last provider-reported usage crosses a
threshold share of the model's context window and stubs old tool results in place
(oversize first), keeping the recent tail untouched and every toolCall paired with
its toolResult.

## Status

v0.2.0. See `PROMPT.md` + `PROMPT-ADDENDUM.md` for the build plan and `REPORT.md`
for the design reference (the studied pi architecture this port follows).
