# Analysis — SHC-0008: React chat package — the Build screen on the real chat

## Metadata

| Field               | Value |
|---------------------|-------|
| **Description**     | The Build screen talks to the real build chat (`POST /api/chat`, SSE) through `useChat` and our own components: streamed text, tool steps, Stop, the draft in the preview, earlier conversations, and reworking an existing app. The mock goes. |
| **Ticket**          | SHC-0008 (after SHC-0007 chat backend) |
| **Endpoints used**  | `POST /api/chat`, `GET /api/chat/conversations`, `GET /api/chat/conversations/{id}`, `POST /api/apps` (deploy), `PUT /api/apps/{slug}/html` (replace) |
| **Key files**       | `frontend/src/pages/BuildPage.tsx`, `DeployBar.tsx`, `AppManageMenu.tsx`, `api/client.ts`, `api/queries.ts`, `shell/DraftFrame.tsx`; new `frontend/src/chat/` |
| **Removed**         | `controller/ChatController`, `service/MockChatService`, `model/Chat*`, `ChatControllerIT`, `sample-apps/draft.html` (if unused elsewhere), `useChat` mock query |
| **Libraries**       | `ai@7.0.146`, `@ai-sdk/react@4.0.150` (pinned in SHC-0006), `react-markdown` |
| **References**      | `.claude/docs/chat-protocol.md` (protocol, client gotchas, server), `.claude/docs/app-origin.md` (draft preview gap), `.claude/docs/app-management.md` |

---

# 1. Problem

## 1.1. What is the problem?
- The chat backend streams real answers from Claude, but the Build screen still posts to the mock and shows one
  canned draft. Builders cannot use what was built in SHC-0007 without DevTools.
- There is no way to continue an earlier conversation or to start changing an existing app with the chat.
- The handoff wants the chat UI as a **reusable package** (embeddable elsewhere later, also as a web component).

## 1.2. Why is it a problem? What may be problematic to implement?
- **Protocol details hide failures** (SHC-0006 gotchas): `DefaultChatTransport` turns a 401 into "Failed to fetch
  the chat response" unless our own `fetch` is passed; the default body carries the whole history (we send only
  the id + new message via `prepareSendMessagesRequest`); an aborted stream ends quietly.
- **Token handling**: the transport must get the bearer token from `api/client.ts` — still "the only place a token
  is attached" — while the chat folder stays free of GUI imports (U2). The GUI hands the package a `fetch`.
- **The silent minute**: while Claude writes the app (30–60 s) no part arrives. The UI must show "writing the
  app…" when text ended but the answer is still running, or it looks frozen.
- **Draft vs. chat**: the HTML arrives as `data-draft` (and as `save_draft`'s tool input). It must go to the preview
  through `onData`, never be rendered in the message list (it is 5–50 KB of code).
- **New vs. rework**: `targetSlug` comes from the Manage menu; the draft's action becomes *Replace HTML* of that
  app instead of *Deploy* (a deploy by name could create a second app by accident).
- **Conversation id**: a new conversation gets its id only in the first answer (`data-conversation`, transient);
  the next message must use it.
- **Bundle size**: +47 KB gzip measured in SHC-0006 for `ai` + `@ai-sdk/react`; markdown adds a little.
- **Previews without data** (U1): a draft has no SDK and no bridge, so an app that calls `shitcloud.db` shows errors
  in the preview until deployed. Accepted; the UI should say so, so builders don't think the app is broken.

## 1.3. How user would like to solve it

### User decisions (Phase 1)

| # | Decision |
|---|---|
| U1 | **Preview without data** (as today): the draft runs without the SDK; data works after deploy |
| U2 | The chat UI is a **copyable folder** `frontend/src/chat/` with no imports from the rest of the GUI (a test enforces it). Web component later |
| U3 | Rework starts from **"Change with AI"** in the app's ⋯ Manage menu (builders), which opens Build with that app; the draft's button becomes **Replace HTML**. The Build screen also **lists earlier conversations** to continue |
| U4 | **Remove the mock** (`/api/chat/mock`, `MockChatService`); without a key Build shows the 503 message |
| — | From SHC-0006: `useChat` + own components; React package now, web component later. From SHC-0007: backend protocol as specified in `chat-protocol.md` |

# 2. Context

## 2.1. Key context
- `BuildPage` today: messages in local state, `useChat` (TanStack mutation) → `/api/chat/mock`, `DraftFrame` +
  `DeployBar` (name + Deploy → `/apps/{slug}`).
- `DraftFrame`: `srcdoc`, sandbox without `allow-same-origin` (opaque origin — must stay).
- `ReplaceHtmlDialog` + `useReplaceHtml(slug)` exist (SHC-0019) and can be reused for the rework action.
- `api/client.ts` attaches tokens and maps 401 to the sign-out flow; `send()` is internal — a `fetch`-shaped
  function must be exported for the transport.
- Known gap (app-origin.md): a draft resolves `/_lib/…` against the GUI domain, so Tailwind/Alpine/Chart.js do not
  load in previews. Planned fix in the roadmap item: `<base href="<apps origin>/">` in `DraftFrame`. Generated
  apps always use the mirror (the guide tells them to), so without the fix every chat draft looks unstyled.
- Server: conversation list `[{id, title, targetSlug, updatedAt}]`; one conversation → `UIMessage[]` with the last
  draft in its `data-draft` part (restoring a conversation restores its preview).

## 2.2. Main flows

```
New app:     Build → type → useChat.sendMessage → POST /api/chat {message}            → stream
             data-conversation → remember id · text → message list · tool-save_draft → chip "Writing the app…/Saved"
             data-draft → onData → preview · finish → ready · Stop → abort fetch
             name + Deploy → POST /api/apps → /apps/{slug}

Continue:    Build → conversation list → GET /api/chat/conversations/{id} → useChat({id, messages})
             preview = last data-draft in those messages; targetSlug from the list item

Rework:      App host page → ⋯ Manage → "Change with AI" → /build?app={slug}
             first message carries targetSlug → model calls get_app → draft (targetSlug set)
             preview action = "Replace HTML of {name}" → PUT /api/apps/{slug}/html → back to /apps/{slug}
```

## 2.3. Current state of things
- Backend chat live on `test` (SHC-0007), checked only through DevTools.
- Frontend has no streaming code, no markdown renderer, no conversation UI.
- Mock still wired: `ChatController` `/api/chat/mock`, `MockChatService` (reads `sample-apps/draft.html`),
  `model/ChatMessage|ChatRequest|ChatResponse`, `queries.ts#useChat`, `types.ts` Chat types, `ChatControllerIT`.

# 3. Options and open points for Phase 2

## 3.1. Package boundary (U2)
- `frontend/src/chat/`: `ChatPanel` (messages + composer + Stop), message/part components (text via markdown, tool
  chips, errors), a `useBuildChat`-style hook around `useChat` with our transport; props in, callbacks out
  (`fetch`, `endpoint`, `conversationId`, `initialMessages`, `targetSlug`, `onDraft`, `onConversation`).
- No imports from `../api`, `../shell`, `../pages`; a Vitest test scans the folder's imports (like the backend's
  `ChatPackageBoundaryTest`). CSS: the package uses the GUI's tokens through class names only, or its own small CSS
  file with CSS variables — decide in Phase 2.

## 3.2. Token-carrying fetch
- Export from `api/client.ts` an `authorizedFetch(input, init)` that adds the bearer and maps 401 to the sign-out
  flow; the Build page passes it to the package. Errors before the stream (`{code,message}`: 503 / 400 / 404)
  must surface with their message (the transport's error is generic otherwise).

## 3.3. Markdown
- `react-markdown` (no raw HTML; links open in a new tab). Answers mention collection names and code; keep code
  blocks readable. Never render the HTML of a draft in the chat.

## 3.4. Draft preview
- U1: no SDK. Show a one-line note above the preview: "Data works after Deploy — the preview has no data."
- `/_lib/` fix: the GUI needs the apps origin for `<base href>`. The server knows `apps.url-pattern`; in path mode
  (`test`) the origin is the pattern without `{slug}/`, in host mode there is no single origin (any app's host
  serves `/_lib/`). Options: a small `GET /api/config` (or a field on `/api/me`) giving the mirror's base URL.
  Isolation unchanged (the draft stays opaque-origin; it only loads scripts from there). To confirm in Phase 2.

## 3.5. Conversation list
- Left column on Build (collapsible on narrow screens): "New app" + earlier conversations (title, app being
  changed, date). Selecting one loads its messages and its last draft.

## 3.6. Rework action
- When the conversation has `targetSlug`, the preview bar shows *Replace HTML of "{name}"* (reusing
  `useReplaceHtml`) instead of name + Deploy; after success → `/apps/{slug}`.

# 4. Acceptance criteria (draft)

1. On `test`, a builder types a request on Build: text streams in, a "writing the app" step shows while the HTML is
   produced, the draft appears in the preview (styled — `/_lib/` loads), and Deploy creates the app.
2. Stop during an answer stops it (server call cancelled) and the composer is usable again.
3. Errors are readable: no key → the 503 message; signed out → the normal sign-in flow; model error → shown in
   the conversation.
4. Earlier conversations are listed; opening one shows its messages and its last draft; continuing works.
5. "Change with AI" on an app opens Build for that app; the draft is offered as *Replace HTML*, which creates a new
   version of that app with its data untouched.
6. The preview says that data works only after deploy.
7. `frontend/src/chat/` imports nothing else from the GUI (test). Mock code and endpoint are gone; all tests green.

# 5. Risks

| Risk | Mitigation |
|---|---|
| Transport hides HTTP errors and 401 | Own `fetch`; error message mapping tested |
| Looks frozen during the silent minute | "Writing the app…" state from `status` + open tool step; the sentence the prompt asks for |
| Big HTML in React state re-renders the chat on every delta | Draft kept outside the message list (`onData` → page state); message components memoised / module-level (assistant-ui freeze lesson) |
| Accidental second app when reworking | Rework conversations only offer Replace HTML |
| Preview shows data errors | Note in the UI (U1); deploy to try data |
| `ai` major updates change the protocol | Versions pinned; protocol stable v5→v7 |
