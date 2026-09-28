# jpi

A minimal Java 21 port of the core of the [pi coding agent](https://github.com/badlogic/pi-mono):
the agent loop, the streaming LLM boundary, and provider adapters — a small,
dependency-light library for building coding agents on the JVM.

- **Pure loop** — `AgentLoop` is a pure function: `(model, context, prompts) → events + transcript`.
  All effects (LLM, tools) sit behind two small interfaces (`StreamFn`, `AgentTool`).
- **Failures are data** — provider errors arrive as assistant messages with
  `stopReason = ERROR/ABORTED`; tool throws become error tool results. The loop never
  throws across boundaries, and a crashed run still ends as a well-formed transcript.
- **Protocol-first streaming** — every provider (Anthropic Messages, OpenAI-compatible
  chat completions, or a scripted fake) normalizes to the same `AssistantMessageEvent`
  vocabulary; aborts cancel the in-flight request and surface as `ABORTED`, promptly.
- **Hooks, not features** — permissions = `beforeToolCall`; steering/follow-up queues;
  `prepareNextTurn` for mid-run model/context swaps; `transformContext` as the
  compaction point.

Dependencies: Jackson + JUnit 5 only. HTTP via `java.net.http` with a hand-rolled SSE
parser. The test suite (171 tests) is fully offline and deterministic.

## Getting it

Requires Java 21+ and Maven. jpi is not on Maven Central yet — build and install
locally:

```sh
git clone git@github.com:hluaguo/jpi.git
cd jpi && mvn install
```

```xml
<dependency>
  <groupId>dev.jpi</groupId>
  <artifactId>jpi</artifactId>
  <version>0.2.0</version>
</dependency>
```

## Quickstart — scripted, zero network

```java
// script the LLM: one tool call, then one answer — no API key, no network
ScriptedProvider provider = ScriptedProvider.builder()
        .toolCall("call_1", "read", Map.of("path", "hello.txt"))
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

agent.prompt("read hello.txt and tell me what's in it");
```

## Real providers

```java
Agent agent = Agent.builder()
        .streamFn(new AnthropicProvider(System.getenv("ANTHROPIC_API_KEY")))
        .model(new Model("claude-sonnet-4-5", "anthropic-messages", "anthropic",
                "https://api.anthropic.com", 200_000, 4096))
        .tools(List.of(new BashTool(), new ReadTool(), new WriteTool()))
        .build();

agent.subscribe(event -> System.out.println(event));
agent.prompt("look around this repository and write a one-paragraph summary");

agent.abort();               // cancels tools *and* the in-flight LLM request
agent.waitForIdle().join();  // resolves only after the run fully settled
```

`OpenAICompletionsProvider` works with any OpenAI-compatible endpoint; both adapters
retry transient failures with classified backoff and mark requests for provider
prefix caching.

## Packages

| Package | What lives there |
|---|---|
| `dev.jpi.agent` | `Agent`, the pure `AgentLoop`, events, tools, hooks, pruning, run stats |
| `dev.jpi.ai` | message model, streaming protocol, retry, cost, error classification, transcript rendering |
| `dev.jpi.ai.providers` | Anthropic Messages + OpenAI-compatible adapters, `ScriptedProvider`, SSE |
| `dev.jpi.json` | golden-pinned JSON wire contract for messages and events |
| `dev.jpi.session` | JSONL session recorder/reader/replayer |
| `dev.jpi.tools` | `bash`, `read`, `write`, `edit` (+ the pure `EditDiff` matching core) |
| `dev.jpi.util` | `CancellationToken` |

## The wire contract (`dev.jpi.json`)

`Json.MAPPER` serializes/deserializes `Message`s, content blocks and `AgentEvent`s
with a `type` discriminator per sealed hierarchy (`user`/`assistant`/`toolResult`,
`text_delta`, `message_start`, …). Unknown fields are tolerated on read; the exact
format is pinned by golden files under `src/test/resources/golden/`. This is the
payload an SSE/RPC bridge streams.

## Resilience, costs, sessions

- **Retry** — wrap any `StreamFn` in `RetryingStreamFn`: exponential backoff with
  jitter, failures classified once at the adapter boundary (`AUTH`, `RATE_LIMIT`,
  `SERVER`, `NETWORK`, `CONTEXT_OVERFLOW`), only transient kinds retried, backoff
  abort-aware, failed attempts buffered so consumers see the final attempt only.
- **Costs** — provider-reported `Usage` is authoritative; `CostCalculator` joins it
  with per-million `Model.Cost` rates; `RunStatsCollector` reduces a run's events
  into tokens/cost/duration stats.
- **Sessions** — `SessionRecorder` appends a flushed JSONL line per event;
  `SessionReader` tolerates a torn final line; `SessionReplayer` rebuilds the
  transcript and can replay a recorded conversation through `ScriptedProvider`
  offline.
- **Context guard** — `transformContext` rewrites the provider-bound message list
  per call; `DeterministicPruner` stubs old tool results (oversize first) when
  usage crosses a share of the context window, keeping every tool call paired
  with its result.
- **`edit` tool** — exact-text replacement with pi's exact-then-fuzzy matching
  (trailing-whitespace, smart-quote, Unicode-dash normalization), CRLF and BOM
  preservation, duplicate/overlap/not-found errors with pi's wording, and
  display + unified-patch diffs in the result details. The line diff is a
  hand-rolled Myers — jpi carries no third-party diff dependency.

## Demo

```sh
mvn -q compile exec:java                          # scripted, offline
mvn -q compile exec:java -Dexec.args=--live       # real Anthropic API; needs ANTHROPIC_API_KEY
mvn test                                          # full offline suite
```

## Development

`mvn test` runs the entire suite offline — providers are tested against canned SSE
bytes and an injectable clock; no network, ever. [`AGENTS.md`](AGENTS.md) documents
the working agreement (commit format, duplication and documentation standards) for
humans and coding agents alike.

## Status & roadmap

v0.2.0 — the API surface is still evolving; expect small breaking changes between
minor versions.

Ported so far: agent loop + hooks, streaming protocol, Anthropic/OpenAI adapters,
retry with error classification, overflow guard, run stats & costs, JSON wire
contract, session recording/replay, `bash`/`read`/`write`/`edit` tools, transcript
text rendering (`Transcripts.contentText`/`render`). Next up, in pi source order:

1. **Token estimation** (pi `pi-ai/utils/estimate`) — heuristic per-message and
   trailing-token estimates, so pruning can act before provider usage arrives.
2. **`search` tool** (pi `harness/tools/search`) — pure-Java grep over the tree,
   ignoring `target/`/`.git/`/`node_modules/`, capped results.

## License

[MIT](LICENSE) — jpi is a port of [pi](https://github.com/badlogic/pi-mono)
(MIT, Mario Zechner / earendil-works); the upstream license notice is preserved
in the LICENSE file.
