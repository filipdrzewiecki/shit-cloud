# Solution design — SHC-0007: Chat backend (Spring AI 2.0 + Claude, SSE)

Input: `analysis.md` (U1–U5), `.claude/docs/chat-protocol.md`.

# 1. What the Spring AI check showed (Spring AI 2.0.1 sources, `AnthropicChatModel.internalStream`)

| Anthropic stream event | What `ChatModel.stream()` gives us |
|---|---|
| text delta | a `ChatResponse` per chunk, immediately |
| `tool_use` block start | **nothing** (tracked internally) |
| tool input JSON deltas | **nothing** (accumulated) |
| `message_delta` (end of step) | one `ChatResponse` with the complete tool calls, finish reason and token usage |

- Tools are executed outside the model (`ToolCallingAdvisor` on `ChatClient`, or our own loop); the model only
  returns tool calls. So **our own step loop** over `ChatModel.stream()` is natural and gives us every protocol
  part we need: text deltas live, then `tool-input-available` → run tool → `tool-output-available` → next step.
- **Confirmed gap:** the app's HTML is `save_draft`'s input, so while Claude writes it (30–60 s) Spring AI emits
  nothing. Mitigation in this design: the system prompt asks the model to say one short sentence before calling
  `save_draft` ("Piszę aplikację…"), and pings every 15 s keep the connection. The client can show "writing the
  app…" when text ended but the step is still open.
- Alternative kept open (D1 below): call the Anthropic Java SDK directly (already on the classpath under Spring AI)
  to get `tool-input-start` the moment the call begins, and even stream the HTML into the preview live.

# 2. Approach

## 2.1. Packages (U1)

```
com.drzewiecki.shitcloud.chat          generic, copyable — imports nothing else from com.drzewiecki.shitcloud
  ChatEngine          step loop: history → Prompt → ChatModel.stream → parts; runs tools; usage; cancel
  ChatTool            plug-in: name, description, JSON schema of the input, run(input, ToolContext) → ToolResult
  ToolResult          output (small JSON shown in the UI) + optional data parts (e.g. data-draft)
  UiPart / UiMessage  records of the AI SDK UI message stream v1 parts and saved messages
  UiStreamWriter      writes `data: <json>\n\n` frames, `: ping`, `[DONE]`; escapes `<`; serialises writes
  ConversationStore   JdbcClient: create, append message, load messages, list per owner
  ChatSettings        @ConfigurationProperties("chat"): model, maxTokens, maxSteps, historyMessages, pingSeconds
com.drzewiecki.shitcloud.buildchat     ShitCloud plug-in set
  BuildChatController POST /api/chat (SSE), GET /api/chat/conversations[/{id}] — builders only
  SaveDraftTool       input {html} → output {saved: true, bytes} + part data-draft {html, targetSlug}
  GetAppTool          input {slug} → output {slug, name, html} (current version; size-capped)
  build-chat-system-prompt.md   rules + SDK basics (window.shitcloud.db, me(), local /_lib/ libraries, Polish UI)
```

`MockChatService` + `ChatController` + `model/Chat*` stay for the Build screen (U2) but move to
`POST /api/chat/mock`; `frontend/src/api/queries.ts` changes that one path. The stream takes `POST /api/chat`
exactly as `chat-protocol.md` specifies, so SHC-0008 changes nothing on the server. SHC-0008 deletes the mock.

A plain unit test (`ChatPackageBoundaryTest`) reads the sources of `chat/` and fails on any import of
`com.drzewiecki.shitcloud.` outside `chat` — that is what keeps it "easy to copy" without a Maven module.

## 2.2. One turn

1. `POST /api/chat {conversationId?, message, targetSlug?}` → validate before streaming (empty text, unknown or
   foreign conversation, unknown `targetSlug`) → plain 400/404 `{code,message}`.
2. New conversation: insert with title = first 60 characters of the message; the stream sends
   `data-conversation {id,title}` (transient) right after `start`.
3. Save the user message; load history (last `historyMessages`, default 20). Older `data-draft` parts are replaced
   by a one-line placeholder — only the latest draft's HTML is resent to the model.
4. System prompt = generic rules + (rework) "You are changing the app `{slug}`; call `get_app` first".
5. Step loop (max `maxSteps`, default 5): `start-step` → `ChatModel.stream(prompt)` with tool definitions and
   `internalToolExecutionEnabled=false`; each text chunk → `text-start` (first) / `text-delta`; at step end
   `text-end`; for each tool call: `tool-input-available` → run → `tool-output-available` (or
   `tool-output-error`) + its data parts → `finish-step`; tool calls present → next step with
   `AssistantMessage(toolCalls)` + `ToolResponseMessage`; none → stop.
6. `finish`, `[DONE]`; save the assistant message (all parts in `UIMessage` shape, transient parts excluded) with
   summed input/output tokens and the model id.

Threading: the controller returns `StreamingResponseBody`; MVC runs it on its async executor, which iterates the
model's `Flux` (blocking) and writes each frame with a flush. A scheduled ping writes `: ping` every 15 s under the
same lock. Async request timeout raised to 10 min (`spring.mvc.async.request-timeout`).

Cancel: a failed write (`IOException`, client gone) → dispose the model subscription → save the partial assistant
message (text so far, finished tools) → return quietly.

Errors: before the first byte → HTTP status + `{code,message}`; after → `error` part, then the stream ends; the
message is saved with what was produced.

## 2.3. Storage — `V3__chat.sql`

```sql
create table chat_conversations (
  id          text primary key,            -- "c_" + random
  owner       text not null,               -- CurrentUser.id (email)
  title       text not null,
  target_slug text,
  created_at  timestamptz not null,
  updated_at  timestamptz not null
);
create index chat_conversations_owner on chat_conversations (owner, updated_at desc);

create table chat_messages (
  conversation_id text not null references chat_conversations(id) on delete cascade,
  seq             int  not null,
  id              text not null,           -- UIMessage id (client's for user messages, "m_…" for answers)
  role            text not null,           -- user | assistant
  parts           jsonb not null,          -- UIMessage parts, exactly as the client renders them
  model           text,
  input_tokens    int,
  output_tokens   int,
  created_at      timestamptz not null,
  primary key (conversation_id, seq)
);
```

- Saved parts follow the client's `UIMessage` shape (`text`, `step-start`, `tool-<name>` with `state`,
  `input`, `output`, `data-draft`) so `GET /api/chat/conversations/{id}` returns `UIMessage[]` as stored.
- `GET /api/chat/conversations` → `[{id, title, targetSlug, updatedAt}]`, newest first, caller's own only.
- Another builder's conversation → 404 (not 403: don't reveal it exists).
- No expiry yet (SHC-0014/0015).

## 2.4. Configuration

```properties
spring.ai.anthropic.api-key=${ANTHROPIC_API_KEY:}
spring.ai.anthropic.chat.options.model=${CHAT_MODEL:claude-sonnet-5-5}
chat.max-tokens=${CHAT_MAX_TOKENS:32000}
chat.max-steps=5
chat.history-messages=20
chat.ping-seconds=15
spring.mvc.async.request-timeout=10m
```

- Dependency: Spring AI BOM `2.0.1` (built on Boot 4.1.1) + `spring-ai-starter-model-anthropic`.
- Without a key the app still starts; the first chat call fails with a clear error (HTTP 503
  `{code:"CHAT_UNAVAILABLE"}` before streaming). Checked in Task 1 — if the starter refuses to start without a key,
  the chat bean is created only when the key is set.
- Tests: a `FakeChatModel` (test classpath) replays scripted steps (text chunks, tool calls, usage) and records
  the prompts it received; no network, no key.

## 2.5. Verification on `test` (backend only, U2)

The Build screen stays on the mock, so the owner checks the real chat from the browser: signed in as builder on
the GUI, a DevTools snippet posts to `/api/chat` with the session's token and prints the parts (provided in Task 10).
Expected: text, `tool-save_draft`, `data-draft` with a working app; a second message continues the conversation.

# 3. Decisions for the owner

| # | Question | Proposal |
|---|---|---|
| D1 | The silent minute while the HTML is written | **Spring AI + mitigation now** (sentence before `save_draft`, pings, client hint). If it feels bad in SHC-0008, swap the model call inside `ChatEngine` for the Anthropic SDK to stream `tool-input-start`/HTML live — the loop and the protocol stay. Alternative: use the Anthropic SDK directly from the start (more code now, no Spring AI in the loop) |
| D2 | Mock path | Mock moves to `/api/chat/mock` (one line in `queries.ts`), the stream takes `/api/chat` per spec |
| D3 | Output cap | `CHAT_MAX_TOKENS` 32 000 (a few-hundred-line app is ~10–15k) |
| D4 | Title | First 60 characters of the first message (no extra model call) |

# 4. Trade-offs

- Own step loop instead of `ChatClient` + `ToolCallingAdvisor`: ~150 lines more, but every part is ours to emit,
  in order, and testable against the captured fixture; the advisor gives no hook between "tool requested" and
  "tool finished" in a form we can stream.
- `StreamingResponseBody` + blocking iteration instead of returning a `Flux`: no WebFlux in an MVC app, and a
  failed write is an `IOException` right where we can dispose the model call.
- Parts stored as JSONB in the client's shape: restore is a plain read; the cost is converting them back to
  Spring AI messages for the prompt (one mapper, unit-tested).
- No ArchUnit dependency for the boundary: a 20-line source scan does it.

# 5. Phase 3 tasks

- Task 1 — `pom.xml`: Spring AI BOM 2.0.1 + `spring-ai-starter-model-anthropic`; `application.properties` chat
  settings; app starts without `ANTHROPIC_API_KEY` (dev, tests)
- Task 2 — `V3__chat.sql` + `chat/ConversationStore`
- Task 3 — `chat/UiPart`, `UiMessage`, `UiStreamWriter` (frames, `<` escaping, pings, `[DONE]`)
- Task 4 — `chat/ChatTool`, `ToolResult`, `ChatSettings`, history → Spring AI messages mapper (old drafts replaced)
- Task 5 — `chat/ChatEngine`: step loop, tool runs, usage, cancel, error part, saving the answer
- Task 6 — `buildchat/SaveDraftTool`, `GetAppTool`, `build-chat-system-prompt.md`
- Task 7 — `buildchat/BuildChatController`: `POST /api/chat` (SSE), conversations list/get, validation, builder
  only; mock → `/api/chat/mock` + `queries.ts` path
- Task 8 — `ChatPackageBoundaryTest`
- Task 9 — Docs: `chat-protocol.md` (paths, the silent-minute note), `AGENTS.md` (endpoints, structure,
  `CHAT_MODEL` / `CHAT_MAX_TOKENS`)
- Task 10 — Deploy on the owner's order (after the Railway outage) + DevTools check snippet for `test`

Phase 4 (tests, planned here, logged in `implementation.md`): `UiStreamWriterTest`, history mapper test,
`ChatEngineTest` on `FakeChatModel` (text only, tool round, two tools, max steps, tool error, model error,
cancel), stream checked part by part against the captured fixture, `BuildChatControllerIT` (403 guest/user,
new conversation + continue, rework with `targetSlug`, list/get own only, 404 foreign, 503 without key),
`ConversationStore` IT, `ChatPackageBoundaryTest`; existing `ChatControllerIT` moved to `/api/chat/mock`.
