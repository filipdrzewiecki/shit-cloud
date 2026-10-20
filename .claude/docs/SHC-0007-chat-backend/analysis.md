# Analysis — SHC-0007: Chat backend (Spring AI 2.0 + Claude, SSE)

## Metadata

| Field               | Value |
|---------------------|-------|
| **Description**     | Real build chat on the server: a builder's message goes to Claude through Spring AI 2.0, the answer streams back as the AI SDK UI message stream (SSE), tool calls (`save_draft`, `get_app`) show as steps, the conversation is saved on the server per builder. |
| **Ticket**          | SHC-0007 (after SHC-0006 decision, SHC-0020 Boot 4) |
| **Endpoints (draft)** | `POST /api/chat` (SSE, spec in `chat-protocol.md`), `GET /api/chat/conversations`, `GET /api/chat/conversations/{id}` |
| **Key classes**     | `ChatController`, `MockChatService` (replaced/moved), new chat package, Spring AI `ChatClient` / `ChatModel` |
| **Related classes** | `AppRegistry` (`get_app`), `SampleAppSeeder`, `SecurityConfig` (builder role), Flyway migrations, `IntegrationTest` |
| **Model**           | `claude-sonnet-5-5` by default, overridable by an env variable |
| **References**      | `.claude/docs/chat-protocol.md`, `shitcloud-handoff.md` §7.1, `.claude/docs/postgres.md`, `.claude/docs/auth.md` |

---

# 1. Problem

## 1.1. What is the problem?
- The Build screen talks to `MockChatService`: every message returns the same canned draft. Nothing is generated.
- The core promise of the product, "an app in ~10 minutes through an AI chat", needs a real model behind the chat.
- Conversations live only in the browser tab; a refresh loses them, and there is no way to come back to a draft.

## 1.2. Why is it a problem? What may be problematic to implement?
- **Tool steps inside a stream.** The protocol shows each tool call as its own part (`tool-input-available`,
  `tool-output-available`) between model steps. Spring AI's default loop runs tools *inside* the stream and hides
  them. The ticket needs either Spring AI's user-controlled tool execution (`internalToolExecutionEnabled=false`,
  we run the loop) or hooks that surface the calls. To be proved in Phase 2.
- **The silent minute.** The whole app arrives as the `html` argument of `save_draft`, i.e. as tool input, not as
  text. Generating a few hundred lines takes 30–60 s. If tool-input deltas are not streamed, the user sees
  nothing in that time; keep-alive pings stop the proxy closing the stream, but the UI needs at least a
  "writing the app…" step (`tool-input-start` as soon as the call begins, if Spring AI exposes it).
- **Framing errors are silent** (SHC-0006 gotcha): a wrong part still answers 200 with an empty message. The stream
  must be tested part by part against the captured fixture.
- **Cancel.** Stop aborts the fetch; the server only notices on its next write. The model call must then be
  disposed (no tokens burnt for nothing) and what was produced saved.
- **No real calls in tests.** The suite must run without `ANTHROPIC_API_KEY` and without network; local dev too.
- **Cost on the owner's key.** Each turn may send the whole conversation plus a full app's HTML. Output size and
  history need caps; token usage should be recorded now (SHC-0015 builds limits on it).
- **Prompt injection.** `get_app` feeds uploaded HTML to the model; that HTML may contain instructions. The tools
  can only read an app and fill the preview; deploying stays a human click. Security never depends on the model.

## 1.3. How user would like to solve it

### User decisions (Phase 1)

| # | Decision |
|---|---|
| U1 | The chat lives in its **own package** inside the app, not a separate Maven module. "Reusable" means **easy to copy** into another project: the generic chat code does not import ShitCloud classes; ShitCloud's tools are a separate plug-in set |
| U2 | **Backend only.** The Build screen keeps the mock until SHC-0008 (the React chat package) |
| U3 | **SSE** (AI SDK UI message stream v1, as decided in SHC-0006), not WebSocket: one request → one one-way stream; plain HTTP keeps the bearer token, 401 handling and the Railway proxy unchanged; `useChat` speaks it natively |
| U4 | Tools in this ticket: **`save_draft` + `get_app`** (create and rework). `list_spaces`, `sample_docs` and prompt tuning stay in SHC-0009 |
| U5 | Default model **Claude Sonnet 5.5** (`claude-sonnet-5-5`), changeable by an env variable |
| — | From SHC-0006: conversations saved on the server per builder with their drafts; chat both creates and reworks apps; the owner's own key in `ANTHROPIC_API_KEY` (already on Railway `api`); no company data in prompts |

# 2. Context

## 2.1. Key context
- Stack is now Boot 4.1.1; **Spring AI 2.0.1** is GA and built on exactly Boot 4.1.1
  (`spring-ai-starter-model-anthropic`, Anthropic Java SDK 2.52 underneath).
- Protocol fully specified in `chat-protocol.md` (request shape, headers, every part, cancel, saved conversations,
  captured example). Nothing to decide there, only to implement.
- The frontend is out of scope (U2), but the saved-conversation shape must be exactly `UIMessage[]` so SHC-0008
  can pass it to `useChat({ id, messages })` unchanged.
- Builders only (`hasRole('BUILDER')`, as the mock); guests and users get 403.
- Data access is `JdbcClient` with hand-written SQL, schema by Flyway (next: `V3__…`); never `stream()` without
  closing (SHC-0018 incident).

## 2.2. Main flows

```
Builder (later: useChat)            API (chat package)                         Claude (Spring AI)
  POST /api/chat {conversationId?, message, targetSlug?}
        ───────────────────────────►  load or create conversation, append user message
                                      build prompt: system + history (capped) + new message
                                      step 1 ───────────────────────────────────►  stream
  ◄─ start, start-step, text-* ──────  text deltas  ◄──────────────────────────────
  ◄─ tool-input-available ───────────  tool call (get_app / save_draft) ◄──────────
                                      run tool (ShitCloud tool set)
  ◄─ tool-output-available ──────────
  ◄─ data-draft (save_draft) ────────
  ◄─ finish-step ───────────────────   step 2 (tool results back to the model) ────►
  ◄─ text-* , finish-step, finish, [DONE]
                                      save assistant message (parts) + token usage
  (Stop) ──── abort ──────────────►   next write fails → dispose model call, save partial answer
```

New conversation: the first answer carries `data-conversation {id, title}` (transient) so the client uses that id.
Rework: `targetSlug` set → the model is told which app it is working on and fetches it with `get_app`; the draft
carries `targetSlug` so the client's Deploy becomes Replace HTML (SHC-0008).

## 2.3. Current state of things
- `ChatController` (`POST /api/chat`, JSON) → `MockChatService` → fixed `ChatResponse(reply, draftHtml)`;
  `model/ChatMessage|ChatRequest|ChatResponse`; `ChatControllerIT` (guest 403, builder gets a reply).
- Frontend `BuildPage` + `queries.ts#useChat` post `{messages}` to `/api/chat` and render the JSON reply.
- No Spring AI dependency, no chat tables, no use of `ANTHROPIC_API_KEY` in code.

# 3. Options and open points for Phase 2

## 3.1. Package layout (U1)
- Generic part, e.g. `com.drzewiecki.shitcloud.chat`: controller-agnostic stream writer (parts → SSE), the
  conversation store, the step/tool loop over Spring AI, a small tool-plug-in interface. No imports from the
  rest of ShitCloud.
- ShitCloud part, e.g. `com.drzewiecki.shitcloud.buildchat`: system prompt, `save_draft`, `get_app`, the
  controller wiring roles and paths.
- Proposal: one small test that fails if `chat` imports anything ShitCloud-specific (keeps "easy to copy" true
  without a module). Cheap; decide in Phase 2.

## 3.2. Where the stream comes from
- Option A: `ChatClient.stream()` with Spring AI running tools internally — simplest, but tool calls may not be
  visible as separate parts. Option B: user-controlled tool execution — we loop steps ourselves and emit every
  part explicitly. Phase 2 starts with a throwaway check against the real API (owner's key, one cheap call) or the
  Spring AI sources to see which events each option exposes, including tool-input streaming.

## 3.3. Path while the mock stays (U2)
- The protocol reserves `POST /api/chat` for the stream, but the Build screen still posts JSON there. Options:
  move the mock to `/api/chat/mock` with a one-line change in `queries.ts`, or put the stream on a new path and
  rename later in SHC-0008. Phase 2 picks one; the spec in `chat-protocol.md` follows.

## 3.4. Storage
- Flyway `V3__chat.sql`: `chat_conversations` (id, owner, title, target_slug, created/updated) and
  `chat_messages` (conversation, sequence, role, `parts` JSONB in `UIMessage` shape, token usage, created).
  Drafts are kept as the `data-draft` part of the message that produced them, so restoring a conversation
  restores its last draft.
- Expiry of old conversations: not now (note for SHC-0014/0015).

## 3.5. Limits and cost
- Output cap (max tokens) big enough for a whole app; history cap (last N messages or a token budget; older
  drafts replaced by a short placeholder so only the latest HTML is resent).
- Token usage per assistant message stored (input/output) for SHC-0015.
- Timeouts: Anthropic client read timeout above the longest expected generation; check Railway's limit for
  long-running HTTP responses.

## 3.6. Running without a key
- Tests: a fake `ChatModel` bean that replays scripted steps (text, tool calls); no network.
- Local dev without `ANTHROPIC_API_KEY`: start anyway; the chat answers with a clear error part (or a fake model in
  the `dev` profile). Railway: the key is set, missing key fails clearly on the first call, not at start.

# 4. Acceptance criteria (draft)

1. A builder's `POST /api/chat` returns `text/event-stream` with the headers from `chat-protocol.md`, and the parts
   in the spec's order; the stream parses with `ai`'s own reader into text, tool and draft parts.
2. "Make a lunch poll" on `test` (real Claude Sonnet 5.5) streams text and ends with a `data-draft` whose HTML runs
   in the apps sandbox.
3. With `targetSlug`, the model calls `get_app` and the draft carries that `targetSlug`.
4. A new conversation gets an id (`data-conversation`); a second message with that id continues it with history.
5. `GET /api/chat/conversations` lists only the caller's conversations; `GET …/{id}` returns `UIMessage[]`
   including the last draft; another builder's id → 404.
6. Stop (client disconnect) stops the model call within seconds and the partial answer is saved.
7. Model or tool failure after the stream started → `error` part; before → HTTP status + `{code,message}`.
8. Guests and plain users → 403; no ShitCloud imports in the generic chat package.
9. Token usage stored per assistant message.
10. Suite green without network or key; the Build screen still works on the mock.

# 5. Risks

| Risk | Mitigation |
|---|---|
| Spring AI hides tool calls inside its stream | Check first in Phase 2; fall back to user-controlled tool execution |
| No feedback during the 30–60 s of HTML generation | `tool-input-start` early / tool-input deltas if exposed; pings every ~15 s |
| Cost per turn grows with history and resent HTML | History cap, only latest draft resent, usage recorded |
| Prompt injection through `get_app` HTML | Tools cannot deploy, delete or read other data; deploy is a human click |
| Long responses cut by a proxy or timeout | Pings; client/server timeouts above generation time; verify on `test` |
| Railway outage still blocking deploys (2026-10-20) | Deploy at the end of the ticket; SHC-0019/0020 checks first |
