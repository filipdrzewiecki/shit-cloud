# Build chat — UI library and wire protocol (reference)

Decided in the SHC-0006 spike (2026-10-20, accepted by the owner). Prototypes of assistant-ui, `useChat` and an own
UI ran against a Java mock server emitting both candidate protocols; they were thrown away, the findings are here.
Owner decisions that shape the chat: React package now, web component later; Spring Boot 4 + Spring AI 2.0 first
(SHC-0020); conversations saved on the server per builder; the chat both creates new apps and reworks existing
ones; the owner's own Anthropic key (`ANTHROPIC_API_KEY` on `api`), no company data in prompts.

## Decision

1. **UI: Vercel AI SDK UI — `useChat` from `@ai-sdk/react` + our own small components** (message list, markdown,
   tool chips, Stop, composer) in the React chat package (SHC-0008). Pinned: `ai@7.0.146`, `@ai-sdk/react@4.0.150`.
2. **Wire protocol: AI SDK UI message stream v1** (SSE), emitted by hand from Spring (SHC-0007). Spec below.
3. assistant-ui is **not** used now, but it runs on the very same protocol (proved in prototype A) — if we ever want
   its richer thread features (branching, editing, attachments UI), the backend does not change.

## Why

Requirements scored (R…): R1 streaming SSE, R2 tool steps visible, R3 draft to the preview not into the chat,
R4 own transport/token/401, R5 Stop, R6 safe markdown, R11 documented protocol reproducible from Java (musts);
R7 our styling, R8 web component later, R9 small/no lock-in, R10 server-held conversations (shoulds).

| Req | Weight | A assistant-ui | **B useChat + own UI** | C deep-chat | D fully own |
|---|---|---|---|---|---|
| R1 streaming over SSE | must | 2 | **2** | 2 | 2 |
| R2 tool steps visible | must | 2 | **2** | 1 (HTML/overwrite workarounds) | 2 |
| R3 draft to preview, not into chat | must | 1 (data UI renders *inside* the message; needs a hand-off to the preview) | **2** (`onData` callback) | 1 | 2 |
| R4 own transport / token / 401 | must | 2 | **2** (`fetch` option) | 2 (handler) | 2 |
| R5 Stop | must | 2 | **2** | 2 | 2 |
| R6 safe markdown | must | 2 | **2** | 1 | 2 |
| R11 documented, versioned, Java-reproducible protocol | must | 2 | **2** | 1 | 2 (we own it) |
| R7 styling with our tokens | should | 2 | **2** | 1 (shadow DOM) | 2 |
| R8 web component later | should | 1 | **1** | 2 | 1 |
| R9 small, no lock-in | should | 0 (+231 KB gzip; zustand, radix, zod, cloud client) | **1** (+47 KB gzip) | 1 (6.7 MB package) | 2 (+0) |
| R10 server-held conversations (U4) | should | 1 (wants to own a thread list) | **2** (`id`, initial `messages`, `prepareSendMessagesRequest`) | 1 | 2 |
| **Weighted (must ×3)** | | 43 | **48** | 35 | 49 |

B and D are level on points. **B wins on what the points don't show**: the protocol is a public, versioned spec
(stable from AI SDK v5 to v7) that a second UI can reuse; the SDK already does the part-merging state machine,
tool-part lifecycle, error and abort states, reasoning parts, tool approvals and resumable streams we will want
later — D would grow all of that by hand to save 47 KB. A costs 231 KB and froze the tab on a harmless coding
pattern (see gotchas). C is a web component (nice for U1) but fights our styling and has no tool/part model.

**Measured** (production build, gzip, React 18 + react-markdown included in every column):
D 83 KB · B 130 KB · A 314 KB · today's whole GUI ≈ 146 KB. Glue code: A 84 lines, B 69, D 63 + 27 (SSE reader).

## Protocol for SHC-0007 (`POST /api/chat`)

**Request** (sent by `useChat` through `DefaultChatTransport`, shaped with `prepareSendMessagesRequest`; the token is
added by our `fetch` like every API call):
```json
{ "conversationId": "c_7f3a", "message": { "id": "u1", "role": "user", "parts": [{ "type": "text", "text": "Make a lunch poll" }] },
  "targetSlug": "lunch-poll" }
```
`targetSlug` is present when the chat reworks an existing app (U5), absent for a new app. Only the new message travels:
the server holds the conversation (U4) and loads it by id.

**Response headers**: `Content-Type: text/event-stream; charset=utf-8`, `Cache-Control: no-cache`,
`x-vercel-ai-ui-message-stream: v1`, `X-Accel-Buffering: no` (no proxy buffering).

**Frames**: each part is `data: <one-line JSON>\n\n`; the stream ends with `data: [DONE]\n\n`. Comment lines
(`: ping\n\n`) every ~15 s keep proxies from closing an idle stream; the parser ignores them.

| Part | JSON | When |
|---|---|---|
| start | `{"type":"start","messageId":"m_…"}` | first |
| start-step / finish-step | `{"type":"start-step"}` | around each model step (a step = one model call; tool calls start a new step) |
| text | `{"type":"text-start","id":"t1"}` · `{"type":"text-delta","id":"t1","delta":"…"}`* · `{"type":"text-end","id":"t1"}` | streamed answer text (markdown) |
| tool call | `{"type":"tool-input-available","toolCallId":"c1","toolName":"list_spaces","input":{…}}` | the model called a tool |
| tool result | `{"type":"tool-output-available","toolCallId":"c1","output":{…}}` or `{"type":"tool-output-error","toolCallId":"c1","errorText":"…"}` | tool finished (outputs shown in the UI must stay small — summaries, not whole datasets) |
| **draft** | `{"type":"data-draft","id":"draft","data":{"html":"<!doctype html>…","targetSlug":"lunch-poll"\|null}}` | `save_draft` produced the whole HTML; fixed `id` so a later draft in the same answer replaces it; the client's `onData` puts it in the preview |
| **conversation** | `{"type":"data-conversation","data":{"id":"c_7f3a","title":"Lunch poll"},"transient":true}` | first answer of a new conversation: tells the client the id to use from now on |
| finish | `{"type":"finish"}` | last part before `[DONE]` |
| error | `{"type":"error","errorText":"…"}` | model/tool failure after the stream started (before it: plain HTTP status + `{code,message}`) |

**Cancel**: the client aborts the fetch (Stop) → the server's next write fails → stop the model call (dispose the
Spring AI stream) and save what was produced so far.

**Saved conversations** (U4): `GET /api/chat/conversations` (list) and `GET /api/chat/conversations/{id}` →
`UIMessage[]` in exactly the shape above (`{id, role, parts:[…]}`), passed to `useChat({ id, messages })`.

**Captured example** (from the Java prototype, parsed correctly by `ai`'s own `readUIMessageStream` into
step-start · tool-list_spaces (output-available) · step-start · text · data-draft):
```
data: {"type":"start","messageId":"msg_74fd…"}

data: {"type":"start-step"}

data: {"type":"tool-input-available","toolCallId":"call_1","toolName":"list_spaces","input":{}}

data: {"type":"tool-output-available","toolCallId":"call_1","output":{"spaces":[{"space":"lunch-poll","collections":["votes"]},{"space":"team-calendar","collections":["users","marks"]}]}}

data: {"type":"finish-step"}

data: {"type":"start-step"}

data: {"type":"text-start","id":"txt_e970…"}

data: {"type":"text-delta","id":"txt_e970…","delta":"Sure"}

…14 more text-delta parts…

data: {"type":"text-end","id":"txt_e970…"}

data: {"type":"data-draft","data":{"html":"<!doctype html><html>…","targetSlug":"lunch-poll"}}

data: {"type":"finish-step"}

data: {"type":"finish"}

data: [DONE]
```

## Gotchas found
- **A framing mistake is silent**: a wrong part still returns 200 and an empty or stuck message. SHC-0007 keeps the
  captured stream as a test fixture and checks emitted streams part by part.
- **`DefaultChatTransport` hides HTTP status**: a 401 surfaces as "Failed to fetch the chat response". Pass our own
  `fetch` (from `api/client.ts`) so 401 goes through the normal sign-out flow.
- **Default request body carries the whole history**; with server-held conversations use
  `prepareSendMessagesRequest` to send only the id and the new message.
- **Abort is quiet**: an aborted stream ends without throwing; the UI resets through `status`. The server sees the
  disconnect on its next write — write often (deltas, pings) so a cancelled model call stops quickly.
- **assistant-ui: never declare message/part components inline** in `ThreadPrimitive.Messages components={{…}}` —
  it re-mounts forever and freezes the tab without any error (React 18, 0.15.30). Module-level components fix it.
- Escape `<` as `<` in emitted JSON (keeps `</script>` out of anything that inlines a stream).

## Consequences for the next tickets
- **SHC-0020 (Boot 4)**: no protocol impact.
- **SHC-0007**: `POST /api/chat` (SSE, spec above), conversations + messages tables (Flyway), save drafts with the
  conversation, Spring AI 2.0 `ChatClient` stream → this protocol; tool calls mapped to `tool-*` parts; `save_draft`
  tool → `data-draft`; cancel on disconnect; keep-alive pings.
- **SHC-0008**: package = `useChat` + our components (reuse `DraftFrame`; markdown via `react-markdown`); Deploy
  becomes Replace HTML when `targetSlug` is set; conversation list; later wrap as a web component (U1).
- **SHC-0009**: tool outputs shown to the user are summaries; `get_app` for rework (U5).
