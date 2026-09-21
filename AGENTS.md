# AGENTS.md

Instructions for coding agents (and humans) working in this repository.

## What this project is

**jpi** — a minimal Java 21 port of the pi coding agent core (`pi-agent-core` +
`pi-ai`, MIT, Mario Zechner / earendil-works): the agent loop, the streaming LLM
boundary, and wire adapters, as a small dependency-light OSS library.

- Out of scope (don't add): TUI, session trees, compaction engine, extensions,
  model catalogs, OAuth.
- Single Maven module, package root `dev.jpi`. Dependencies: **Jackson + JUnit 5
  only**; HTTP via `java.net.http`; hand-rolled SSE parser.
- All tests offline & deterministic (`ScriptedProvider`; adapters tested against
  canned bytes). No network in the suite, ever.
- Failures are **data, not exceptions**: provider failures → assistant messages
  with `stopReason = ERROR/ABORTED`; tool throws → error tool results.
- Message/event types are sealed interfaces + records; streaming funnels through
  `EventStream`.

## Documentation standard

Docstrings record **why**, not what.

- Class docs state the problem the abstraction solves — never paraphrase methods.
- No docstrings on obvious functions; a restating doc is noise and rots.
- Keep docs carrying contracts you can't guess: blocking behavior, wire-protocol
  quirks (usage arrives after `finish_reason` in OpenAI), invariants (`terminate`
  fires only when every result in the batch sets it).
- Touching a file whose doc restates mechanics? Fix it in the same change.

## Duplication standard

The adapters (`AnthropicProvider`, `OpenAICompletionsProvider`) deliberately keep
their own request/SSE/delta logic — each reads as a standalone description of its
wire protocol. Review must not flag this. Extract only on a third real caller
(rule of three) or for behavior that must not drift (protocol invariants).

## Commit standard

Conventional Commits, one commit per ticket or coherent slice:

```
<type>(<scope>): <imperative summary, ≤72 chars> [T<n>]

[optional body: what + why, blank-line separated]
pi: <pi file/feature ported>
```

- Types: `feat` `fix` `test` `docs` `refactor` `chore` `build`.
- Scope: area — `loop`, `ai`, `stream`, `adapters`, `tools`, `json`, `session`, `stats`.
- The `pi:` trailer cites the ported origin (e.g. `pi: provider-retry.js,
  overflow.js`) — required when porting; it becomes the report's traceability table.
- Never commit: secrets/API keys, `target/`, personal data in fixtures.
- No force-push to `main`; linear history on working branches.

Examples:

```
feat(loop): fail tool calls from truncated responses [T3]
feat(adapters): retry with backoff and error classification [T10]

pi: provider-retry.js, overflow.js
test(json): golden files for event serialization [T9]
```

## Working agreement

- TDD at the agreed seams only; one vertical slice per red→green cycle; no
  speculative hooks.
- `mvn -q compile` often; single test classes often; full `mvn test` before commit.
- Java 21 (`maven.compiler.release=21`); records/sealed over mutable POJOs.
