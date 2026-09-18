# jpi — Implementation Plan

> Paste this file (or reference it with `@PROMPT.md`) as the opening prompt of a fresh
> agent session started inside `/Users/hugolau/project/cityuhk/cs3383/jpi`.

---

## Mission

Build **jpi**: a minimal Java 21 port of the core of the pi coding agent — the agent
loop, the streaming LLM boundary, and provider adapters — for the CS3383 course
project and open-source release.

**Read `REPORT.md` first.** It documents the studied pi architecture, the module
layout, and the test seams already agreed with the user. This file is the build
plan; `REPORT.md` is the design reference. Treat both as the spec.

## Ground rules

- Java 21, single Maven module. Dependencies: **Jackson** and **JUnit 5 only** —
  no SDKs, no libraries beyond the JDK (HTTP via `java.net.http`).
- Package root `dev.jpi`, sub-packages `dev.jpi.ai`, `dev.jpi.ai.providers`,
  `dev.jpi.agent`, `dev.jpi.tools`.
- Style: sealed interfaces + records for all message/event types; **failures are
  data** (`StopReason.ERROR/ABORTED`), never exceptions across module boundaries.
- All tests **offline and deterministic**; no real network in the suite.
- Work per the **implement** workflow: use the **tdd** skill at the seams listed
  below (already agreed — do not invent new seams), one vertical slice per
  red→green cycle. `mvn -q compile` often, run single test classes often, full
  `mvn test` at the end of each ticket. **Commit after every ticket.**
- Scope guard: no sessions, no compaction, no TUI, no OAuth, no model catalogs.

## Tickets (in order; each ends green + committed)

### T0 — Scaffold
`pom.xml` (Java 21, Jackson, JUnit 5), `.gitignore`, `git init`, package dirs,
README stub. Verify `mvn test` runs one trivial test.

### T1 — `EventStream<T,R>` (seam 1)
`dev.jpi.ai.EventStream`: push/pull async queue — `push(event)`, `end(result)`,
iteration until a terminal event, `result()` future, driven by an `isComplete`
predicate + `extractResult` function (mirrors pi's `EventStream`).
Tests: ordering, terminal semantics, `result()`, producer on a separate thread.

### T2 — Core model types + `ScriptedProvider`
Types emerge with their tests: `Content` (text/image/thinking/toolCall),
`Message` (user/assistant/toolResult), `Usage`, `StopReason`,
`AssistantMessageEvent` (pi's protocol: start; text/thinking/toolcall
start|delta|end; done; error), `Model`, `Context`, `Tool`, `StreamFn`,
`AssistantMessageEventStream`.
`dev.jpi.ai.providers.ScriptedProvider`: a `StreamFn` built from canned event
sequences (fluent builder) — the test backbone for all loop tickets.

### T3 — `AgentLoop` (seam 2)
`AgentContext`, `AgentTool`, `AgentToolResult`, `AgentEvent`, `AgentLoopConfig`
(hooks: `beforeToolCall`, `afterToolCall`, `shouldStopAfterTurn`,
`prepareNextTurn`, `getSteeringMessages`, `getFollowUpMessages`,
`toolExecution` sequential|parallel), `AgentLoop`.
Red→green slices, one test at a time:
1. text-only turn → exact event sequence + transcript
2. tool-call turn → tool_execution_* events, toolResult appended, second LLM call
3. `stopReason=length` → every tool call failed with a truncation error, none executed
4. `beforeToolCall` block → error tool result, `execute` never invoked
5. `afterToolCall` override → content/isError mutated
6. abort mid-tool → aborted tool results, loop ends with stopReason aborted
7. steering injected between turns; follow-up message keeps the loop alive
8. `shouldStopAfterTurn` stops; `prepareNextTurn` can swap model/context
9. parallel mode: `tool_execution_end` in completion order, toolResult messages in source order

### T4 — `Agent` wrapper (seam 3)
State (systemPrompt, model, thinkingLevel, tools, messages), `subscribe`
(synchronous, subscription order), `prompt`/`continue`, `steer`/`followUp`
queues (one-at-a-time | all), `abort`, `waitForIdle`.
Tests: listener ordering, queue semantics, isStreaming lifecycle, waitForIdle.

### T5 — Anthropic adapter (seam 4)
`dev.jpi.ai.providers.AnthropicProvider`: `Context → /v1/messages` request JSON
(system prompt; text/image/tool_use/tool_result mapping; tools) and SSE response
→ `AssistantMessageEvent` sequence (`message_start`, `content_block_*`,
`message_delta` with usage/stop_reason, `message_stop`). Hand-rolled SSE parser;
HTTP via `java.net.http`.
Tests: request JSON vs canned expected JSON; canned SSE bytes → expected event
sequence + final message (usage, `stopReason=toolUse`, tool args assembled from
deltas). No network.

### T6 — OpenAI-compatible adapter (seam 5)
Same for `/v1/chat/completions`: request mapping, SSE chunk parsing,
index-based `tool_calls` delta assembly, `finish_reason → StopReason`, usage.
Offline tests as in T5.

### T7 — Demo tools + example
`dev.jpi.tools`: `ReadTool`, `WriteTool`, `BashTool` (ProcessBuilder + timeout),
and `examples/Demo.java` wiring `Agent` + `ScriptedProvider` so the loop can be
demoed with zero network. Keep thin.

### T8 — Full suite, docs, review, tag
`mvn test` green; javadoc on public APIs; README quickstart (<30-line example
with `ScriptedProvider`); run the **code-review** skill with fixed point = the
T0 commit (spec source: this file + `REPORT.md`; no issue tracker — review
against the tickets). Fix findings, final commit, tag `v0.1.0`.

## Definition of done

- All agreed-seam tests green via `mvn test`; suite runs offline in seconds
- Public API javadoc'd; README quickstart works
- One commit per ticket; clean `git log`
- code-review findings addressed or explicitly waived
