# AGENTS.md

Instructions for coding agents (and humans) working in this repository.

## What this project is

**jpi** is a minimal Java 21 port of the core of the [pi coding agent](https://github.com/badlogic/pi-mono)
(`pi-agent-core` + `pi-ai`, MIT, Mario Zechner / earendil-works): the agent loop, the
streaming LLM boundary, and wire adapters — extracted from the full app into a small,
dependency-light OSS library.

- The app layer of pi (TUI, sessions, compaction, extensions, model catalogs, OAuth)
  is deliberately **out of scope**. Don't add it.
- `REPORT.md` is the design reference (the studied pi architecture and what jpi takes/drops).
  `PROMPT.md` is the build plan (tickets T0–T8, test seams). Read both before larger changes.
- Single Maven module, package root `dev.jpi` (`ai`, `ai.providers`, `agent`, `tools`, `examples`).
  Dependencies: **Jackson and JUnit 5 only** — HTTP via `java.net.http`, hand-rolled SSE parser.
- All tests are **offline and deterministic** (`ScriptedProvider` fakes the LLM; adapters are
  tested against canned request/response bytes). No network in the suite, ever.
- Failures are **data, not exceptions** across module boundaries: provider failures become
  assistant messages with `stopReason = ERROR/ABORTED`; tool throws become error tool
  results. The loop is a pure, total function.
- Message/event types are sealed interfaces + records; streaming funnels through
  `EventStream` / `AssistantMessageEventStream`.

## Documentation standard

Docstrings exist to record **why**, not what.

- A class/interface doc states *why the abstraction exists*: the problem it solves, the
  design decision it encodes, the constraint it protects. Not a paraphrase of its methods.
  Example: `StreamFn` documents *why* implementations must not throw; `AgentLoopConfig`
  documents *why* hooks exist instead of features.
- **No docstrings on obvious functions.** If the name and signature already say it
  (`result()`, `calls()`, `processLine(line)`), leave it undocumented. A restating
  docstring is noise and rots fast.
- Keep a function docstring only when it carries something non-obvious: a contract you
  couldn't guess (`prompt()` blocks the caller), a wire-protocol quirk (`tool_result`
  rides on user messages in Anthropic; usage arrives after `finish_reason` in OpenAI),
  an invariant (a `Done` event never carries `stopReason = ERROR`), or a rule
  (`terminate` only fires when *every* result in the batch sets it).
- When you touch code whose docstring restates mechanics, fix the docstring in the same
  change — the doc standard applies to the whole file, not just your hunk.

## Duplication standard

**Do not deduplicate for deduplication's sake.**

- The wire adapters (`AnthropicProvider`, `OpenAICompletionsProvider`, and their stream
  parsers) deliberately **keep their own logic**: request building, SSE handling, delta
  assembly, and error plumbing are each self-contained. The duplication is intentional —
  each adapter reads as a standalone description of its wire protocol, and the two
  protocols evolve independently. Code review must not flag this as a defect.
- Extract shared code only when a **third real caller** exists (rule of three), or when
  the duplication is of *behavior* that must not drift (protocol invariants, e.g. the
  terminal-event contract shared via `AssistantMessageEvent`).
- Within a single class, small helpers are fine; reaching for inheritance, generics, or
  abstraction layers to remove a few repeated lines is not.

## Working agreement

- Test-first at the agreed seams only (`EventStream`, `AgentLoop`, `Agent`, adapter
  mappings); one vertical slice per red→green cycle; no speculative hooks.
- `mvn -q compile` often; single test classes often; full `mvn test` before committing.
- One commit per ticket/feature; keep `git log` clean.
- Java 21 (`maven.compiler.release=21`); records and sealed interfaces over mutable
  POJOs for message/event types.
