# jpi — Study Report: the pi Agent Core

Source studied: `@earendil-works/pi-agent-core@0.85.1`, `@earendil-works/pi-ai@0.85.1`,
`@earendil-works/pi-coding-agent` (dist + docs), all MIT licensed, by Mario Zechner / earendil-works.
Goal: extract the *core* of pi (agent loop, provider abstraction, streaming) into a small,
dependency-light Java module (`jpi`) usable for the CS3383 course project and as an OSS library.

---

## 1. How pi is layered

```
┌────────────────────────────────────────────────────────────┐
│ pi-coding-agent   (the app: TUI, sessions, compaction,     │
│                    extensions, skills, RPC, SDK)           │
├────────────────────────────────────────────────────────────┤
│ pi-agent-core     (Agent loop, tools, events, state,       │
│                    steering/follow-up queues)              │
├────────────────────────────────────────────────────────────┤
│ pi-ai             (unified LLM API: message model,         │
│                    streaming protocol, providers, models)  │
└────────────────────────────────────────────────────────────┘
```

The whole app is a thin shell over a tiny **pure agent loop**. UI, sessions,
compaction and extensions all hang off the loop's *events* and *hooks* — they are
not baked in. That is the property worth preserving in jpi.

---

## 2. pi-ai — the LLM boundary

### 2.1 Message model

Three message roles, four content block types (`pi-ai/dist/types.d.ts`):

| Type | Fields |
|---|---|
| `UserMessage` | `content: string \| (text\|image)[]`, `timestamp` |
| `AssistantMessage` | `content: (text\|thinking\|toolCall)[]`, `api`, `provider`, `model`, `usage`, `stopReason`, `errorMessage?`, `timestamp` |
| `ToolResultMessage` | `toolCallId`, `toolName`, `content: (text\|image)[]`, `details?`, `isError`, `timestamp` |

Content blocks:

- `TextContent` — `{ type: "text", text }`
- `ImageContent` — `{ type: "image", data (base64), mimeType }`
- `ThinkingContent` — `{ type: "thinking", thinking, thinkingSignature?, redacted? }`
- `ToolCall` — `{ type: "toolCall", id, name, arguments }`

`StopReason = "pending" | "stop" | "length" | "toolUse" | "error" | "aborted" | "deferred"`.

**Key design decision:** failures are *data, not exceptions*. A failed or aborted
LLM call produces a well-formed `AssistantMessage` with `stopReason: "error" |
"aborted"` and an `errorMessage`. The loop never has to catch provider exceptions.

`Usage` carries token counts *and* a cost breakdown (input / output / cacheRead /
cacheWrite, computed from per-model pricing). `Context = { systemPrompt?, messages, tools? }`
is the exact payload handed to a provider.

### 2.2 Streaming protocol

Every provider call returns an `AssistantMessageEventStream` — an async push/pull
queue (`pi-ai/dist/utils/event-stream.d.ts`) that yields `AssistantMessageEvent`s
and finishes with a final `AssistantMessage` via `result()`:

| Event | Meaning |
|---|---|
| `start` | stream accepted; carries the partial (empty) assistant message |
| `text_start / text_delta / text_end` | incremental text block |
| `thinking_start / thinking_delta / thinking_end` | incremental reasoning block |
| `toolcall_start / toolcall_delta / toolcall_end` | incremental tool-call arguments (partial JSON) |
| `done` | terminal; `reason ∈ {stop, length, toolUse, deferred}` + final message |
| `error` | terminal; `reason ∈ {aborted, error}` + error message |

Each event carries `partial: AssistantMessage` — a live "response so far" the UI
can render. This one protocol normalizes all providers.

### 2.3 Provider abstraction

- **API adapters** (one module per *wire protocol*): `anthropic-messages`,
  `openai-completions`, `openai-responses`, `google-generative-ai`,
  `bedrock-converse-stream`, … Each exports `stream` / `streamSimple` of the same
  shape: `(model, context, options) → AssistantMessageEventStream`.
- **Provider** (`models.d.ts`): owns `id`, `name`, `baseUrl`, `auth`, `getModels()`,
  and stream behavior. The `Models` registry resolves auth, refreshes model
  catalogs, and delegates each request to the provider that owns the model.
- **Model metadata**: `id`, `api`, `provider`, `baseUrl`, `contextWindow`,
  `maxTokens`, `cost` (with tiers), `thinkingLevelMap`, `compat` flags (per-provider
  quirks for OpenAI-compatible endpoints).
- **StreamFn contract** (used by the agent loop): *must not throw*; failures are
  encoded in the returned stream. Auth can be resolved per-call via `getApiKey`
  (short-lived OAuth tokens).

---

## 3. pi-agent-core — the loop

### 3.1 Data model

- `AgentMessage = Message | <app-defined custom messages>` — apps can extend the
  transcript with their own roles via declaration merging; `convertToLlm` maps
  them to LLM messages (or filters UI-only ones out) at the last moment.
- `AgentContext = { systemPrompt, messages: AgentMessage[], tools }` — an immutable-ish
  snapshot passed into the loop.
- `AgentTool extends Tool`: adds `label`, `prepareArguments` (pre-validation shim),
  `execute(toolCallId, args, signal, onUpdate) → AgentToolResult`, optional
  `executionMode: "sequential" | "parallel"`, `replay` policy.
- `AgentToolResult = { content: (text|image)[], details, usage?, terminate?, addedToolNames? }`.
  Tools *throw* on failure; the loop converts throws into error tool results.

### 3.2 Events (the UI contract)

```
agent_start
  turn_start
    message_start    (user prompt / injected steering message)
    message_end
    message_start    (assistant partial)
    message_update   (per streaming delta: carries the raw AssistantMessageEvent)
    message_end      (final assistant message)
    tool_execution_start / tool_execution_update / tool_execution_end
    message_start / message_end  (each toolResult message)
  turn_end           (assistant message + tool results)
agent_end            (all new messages)
```

The entire app (TUI, session JSONL persistence, compaction triggers) is a
*consumer* of this event stream. Nothing else is coupled to the loop.

### 3.3 The loop itself (`agent-loop.js`, ~560 lines)

Pseudocode of `runLoop`:

```
pending = getSteeringMessages()          # user may have typed while waiting
while true:                              # OUTER loop: follow-ups
    hasMoreToolCalls = true
    while hasMoreToolCalls or pending:   # INNER loop: turns
        if lastCompletedTurn:
            prepareNextTurn(lastCompletedTurn)   # may swap context/model/thinking (e.g. compaction)
            pending |= getSteeringMessages()
            emit turn_start
        inject pending messages into context     # message_start/end per message
        msg = streamAssistantResponse()          # see below
        if msg.stopReason in {error, aborted}:
            emit turn_end; emit agent_end; return
        toolCalls = msg.content where type=toolCall
        if toolCalls:
            if msg.stopReason == "length":
                fail all tool calls ("arguments may be truncated")   # safety
            else:
                results = executeToolCalls(...)  # sequential or parallel
            append toolResult messages to context
            hasMoreToolCalls = not all(results.terminate)
        emit turn_end
        if shouldStopAfterTurn(...): emit agent_end; return
        pending = getSteeringMessages()          # steering between turns
    followUps = getFollowUpMessages()            # agent would stop…
    if followUps: pending = followUps; continue  # …but there is more to do
    break
emit agent_end
```

`streamAssistantResponse` — the only place where `AgentMessage[]` is transformed:

1. `transformContext(messages)` — prune/summarize at AgentMessage level (compaction hook).
2. `convertToLlm(messages)` — map/filter to wire `Message[]`.
3. build `Context { systemPrompt, messages, tools }`.
4. resolve API key (`getApiKey(provider)`), call `streamFn(model, context, options)`.
5. pump events: `start` → append partial to context + `message_start`;
   deltas → replace last context message + `message_update`;
   `done`/`error` → store final message + `message_end`.

### 3.4 Tool execution pipeline

Per tool call: **prepare → execute → finalize**.

1. **prepare**: find tool by name (missing ⇒ error result), optional
   `prepareArguments`, `validateToolArguments` against the tool's schema,
   `beforeToolCall` hook — may `{ block: true, reason }` ⇒ error tool result
   without execution (permission gate).
2. **execute**: `tool.execute(id, args, signal, onUpdate)`; `onUpdate` streams
   `tool_execution_update` events; a throw becomes an error tool result.
3. **finalize**: `afterToolCall` hook may field-wise override content/details/
   isError/usage/terminate; then `tool_execution_end` + toolResult message events.

Modes:

- `sequential`: one at a time, abort checked between calls.
- `parallel` (default): prepares sequentially (so `beforeToolCall` gates stay ordered),
  then executes concurrently; `tool_execution_end` emitted in *completion* order;
  toolResult *messages* emitted later in assistant *source* order.
- `terminate` early-exit only fires when **every** result in the batch sets it.
- If `signal.aborted` mid-batch, remaining calls become "Operation aborted" results.

### 3.5 The `Agent` class (stateful wrapper)

Owns `_state` (systemPrompt, model, thinkingLevel, tools, messages), the listener
list, and two queues:

- `steer(msg)` — injected after the *current* assistant turn's tools finish
  (`steeringMode: one-at-a-time | all`).
- `followUp(msg)` — injected only when the agent *would otherwise stop*.
- `prompt(input)` / `continue()` start runs; `abort()` cancels via `AbortSignal`;
  `waitForIdle()` resolves after `agent_end` listeners settle; listeners are
  awaited in subscription order and count into run settlement.

### 3.6 Why this design is good (and worth porting)

- The loop is a **pure function**: `(prompts, context, config, streamFn) → events`.
  All effects (LLM, tools) sit behind two small interfaces (`StreamFn`, `AgentTool`).
- **Failures as data** at both boundaries: provider errors are `stopReason:"error"`;
  tool throws become error tool results. The loop cannot crash the app.
- **Protocol-first streaming**: one event vocabulary for every provider.
- **Hook points instead of features**: compaction = `transformContext` +
  `prepareNextTurn`; permissions = `beforeToolCall`; sub-agents, UI, sessions are
  all downstream consumers.

---

## 4. What jpi takes — and drops

**Takes (the core):**
- message/content model (`UserMessage`, `AssistantMessage`, `ToolResultMessage`,
  text/image/thinking/toolCall blocks), `Usage`, `StopReason`
- streaming protocol (`AssistantMessageEvent`) + `EventStream` machinery
- provider abstraction: `Provider`, `Model`, `StreamFn` contract, wire adapters
  (Anthropic Messages + OpenAI-compatible chat completions) with SSE parsing
- the agent loop with all hooks (`beforeToolCall`, `afterToolCall`,
  `shouldStopAfterTurn`, `prepareNextTurn`, steering/follow-up queues,
  sequential/parallel tool execution, truncation safety, abort)
- lifecycle events (`AgentEvent`)
- a fake/scripted provider for tests and demos (pi tests its loop the same way)

**Drops (app-level, out of scope):**
OAuth/subscription auth, generated model catalogs, JSONL session trees, compaction
engine, TUI, extension loader, RPC mode, image generation.

## 5. Java mapping decisions

| TS concept | Java 21 mapping |
|---|---|
| discriminated unions | `sealed interface` + `record`s |
| `EventStream` (async queue + `result()`) | `EventStream<T>`: `BlockingQueue` + `CompletableFuture<R>`, `Iterator`/`Stream`-friendly |
| `AbortSignal` | `jpi.util.CancellationToken` (thin wrapper over `Thread`/`volatile flag` + listeners) or `java.util.concurrent.atomic` based |
| `StreamFn` | `@FunctionalInterface StreamFn { AssistantMessageEventStream stream(Model, Context, StreamOptions) }` |
| TypeBox schema validation | lightweight: tools declare a JSON-Schema-shaped `Map`; args arrive as `Map<String,Object>`; optional validator, no codegen |
| provider SDKs | none — plain `java.net.http.HttpClient` + hand-rolled SSE parser (dependency-light, teaching-friendly) |
| JSON | Jackson (single dependency) |
| awaited listeners | `Agent.emit` invokes `Consumer<AgentEvent>` synchronously, in subscription order |

Module layout:

```
jpi/
  pom.xml                        (Java 21, JUnit 5, Jackson)
  src/main/java/dev/jpi/
    ai/        Message, Content, Usage, StopReason, Model, Context, Tool,
               AssistantMessageEvent, EventStream, StreamFn, StreamOptions,
               Provider, Models
    ai/providers/  AnthropicProvider, OpenAICompletionsProvider,
               ScriptedProvider (test/demo), SseParser, Json (helpers)
    agent/     AgentTool, AgentToolResult, AgentContext, AgentEvent,
               AgentLoopConfig (hooks), AgentLoop, Agent
    tools/     ReadTool, WriteTool, EditTool, BashTool  (demo harness tools)
  src/test/java/dev/jpi/   (JUnit 5, TDD at agreed seams)
```

## 6. Test seams (agreed with user before implementation)

1. **`EventStream`** — push/pull ordering, terminal `done`/`error`, `result()`,
   producer on another thread.
2. **`AgentLoop`** with a `ScriptedProvider` (canned event sequences):
   - plain text turn → exact event sequence + final transcript
   - tool-call turn → tool events, toolResult appended, loop calls LLM again
   - `stopReason=length` → all tool calls failed with truncation error
   - `beforeToolCall` block → error tool result, no execution
   - `afterToolCall` override → result mutated
   - abort mid-run → `aborted` propagation
   - steering message injected between turns; follow-up keeps loop alive
   - `shouldStopAfterTurn` / `prepareNextTurn` honored
3. **`Agent`** — subscribe order, `steer`/`followUp` queues, `waitForIdle`.
4. **Anthropic adapter mapping** — `Context → request JSON` and canned
   `SSE chunks → AssistantMessageEvent` sequence (offline, no network).
5. **OpenAI-completions adapter mapping** — same, including tool-call argument
   delta assembly and usage extraction.

Non-goals for tests: real network calls (manual smoke only), TUI, persistence.
