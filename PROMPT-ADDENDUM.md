# jpi — Addendum Build Plan (T9–T14)

> Use in a **fresh session** in this directory, **after** the base build
> (`PROMPT.md`) is complete and tagged `v0.1.0`.
> Fixed point for the final review: tag `v0.1.0`.
> Do not renumber or modify tickets T0–T8; this plan appends T9–T14.

---

## Mission

Five pi-derived capabilities that jpi needs for the DataForge project, kept out of
the base build to leave T0–T8 untouched. Each maps to a specific pi feature —
cite it in the commit message. Read `REPORT.md` for architecture context and
`PROMPT.md` for the ground rules (they still apply: Java 21, Maven, Jackson +
JUnit 5 only, sealed/record style, failures as data, offline tests, TDD, one
commit per ticket).

Prerequisite check before starting: `git rev-parse v0.1.0` resolves and
`mvn test` is green.

## Tickets

### T9 — JSON serialization of messages & events (`dev.jpi.json`)
Pi reference: RPC mode framing + `docs/json.md`.
Jackson mappers for `Message` and `AgentEvent` with stable camelCase field names
(matching pi's RPC vocabulary: `type`, `toolCallId`, `stopReason`, …).
Round-trip tests (object → JSON → object equals) + golden files pinned under
`src/test/resources/golden/`. Unknown fields tolerated on read (forward compat).
This is the contract the DataForge SSE bridge will stream.

### T10 — Provider resilience: retry + error classification
Pi reference: `pi-ai/dist/utils/provider-retry.js`, `overflow.js`, `retry.js`.
A `StreamFn` decorator: exponential backoff + jitter, retry on 429/5xx/network
errors/timeouts, honor `CancellationToken`, configurable `maxRetries` and
`maxRetryDelayMs` (fail fast past the cap). Classify errors into a small enum
(`AUTH`, `RATE_LIMIT`, `SERVER`, `NETWORK`, `CONTEXT_OVERFLOW`, `UNKNOWN`) carried
on the error `AssistantMessage` (add a `diagnostics` field, mirroring pi).
Tests: a flaky fake HTTP layer failing N times then succeeding — no real network.

### T11 — Token estimation, cost calculation, run stats
Pi reference: `pi-ai/dist/utils/estimate.js`, `calculateCost`, model `cost` tables.
- `estimateTokens(messages)` — chars-based heuristic for the context guard.
- `Model.cost`: per-million rates `input/output/cacheRead/cacheWrite` (+ optional
  tiered pricing later — out of scope now).
- `CostCalculator.costOf(usage, model)`.
- `RunStats`: reducer over `AgentEvent`s → per-run totals (tokens by kind, cache
  hits, cost, duration, message/tool counts). This powers the UI cost badges.
Tests: golden numbers from worked examples; reducer over scripted event streams.

### T12 — Session recorder & replayer (JSONL)
Pi reference: `docs/session-format.md` (simplified: append-only, no tree/fork).
`SessionRecorder`: append every `AgentEvent` of a run to a versioned JSONL file
(`{"v":1,"ts":...,"event":{...}}`), one line each, flush per line.
`SessionReader`: list sessions, read events, **tolerate a torn final line**
(crash safety). `SessionReplayer`: rebuild the transcript + feed recorded events
to a UI/state consumer; recorded assistant responses must be replayable through
`ScriptedProvider` (demo-day fallback).
Tests: record → read round-trip, torn-tail recovery, replay determinism.

### T13 — Context-window guard (`ContextTransformer`)
Pi reference: loop `transformContext` hook + compaction trigger (simplified).
Strategy interface plugged into `AgentLoopConfig.transformContext`.
`DeterministicPruner`: when `estimateTokens` > threshold % of
`model.contextWindow`, drop oldest *complete turns'* tool results and oversize
tool outputs first; never separate an assistant `toolCall` from its
`toolResult`; always keep system prompt + the most recent N messages.
LLM-summarization transformer: optional stretch behind the same interface.
Tests: pruning invariants incl. the toolCall/toolResult pairing contract
(the loop's continue path rejects an orphaned toolResult — see T3 note).

### T14 — Full suite, docs, review, tag `v0.2.0`
`mvn test` green; javadoc; README section per new feature; run the **code-review**
skill with fixed point = `v0.1.0` (spec: this file). Fix findings, final commit,
tag `v0.2.0`.

## Parallelization note

The DataForge build needs **T9 first** (SSE bridge contract). T10–T13 can land
afterwards in any order. If DataForge starts before this addendum finishes,
sequence: T9 → DataForge bridge → T10–T13.

## Definition of done

- `mvn test` green offline; one commit per ticket; clean history on top of `v0.1.0`
- Each commit message cites the pi feature it ports (traceability for the report)
- code-review findings addressed or explicitly waived; `v0.2.0` tagged
