# Solution design — SHC-0008: React chat package — the Build screen on the real chat

Input: `analysis.md` (U1–U4), `.claude/docs/chat-protocol.md`. Checked against the pinned `ai@7.0.146` /
`@ai-sdk/react@4.0.150` type definitions.

# 1. Facts from the library that shape the design
- `useChat({ id, messages, transport, onData, onError, onFinish, throttle })` → `{ messages, status ('submitted' |
  'streaming' | 'ready' | 'error'), error, sendMessage, stop, clearError, setMessages }`. A different `id` means a
  different chat; switching conversations = remounting the panel with a new `key`.
- `DefaultChatTransport({ api, fetch, prepareSendMessagesRequest })`: on a non-OK response it throws
  `new Error(<raw body>)`. Our `fetch` therefore throws our own `ApiError` first (readable message, 401 → sign-out).
- `onData` receives every data part, transient ones included (`data-conversation`); `data-draft` (fixed id) also
  stays in the message, so a restored conversation still holds its last draft.
- Tool parts arrive as `tool-<name>` with `state` `input-available` → `output-available` / `output-error`; the live
  `save_draft` part carries the whole HTML in `input` (saved conversations hold a placeholder) — never render it.

# 2. Approach

## 2.1. The package — `frontend/src/chat/` (U2)

```
chat/
  ChatPanel.tsx      messages + composer + Stop; props in, callbacks out (below)
  parts.tsx          module-level part components: TextPart (markdown), ToolPart (chip), DraftNote, StepSeparator
  Markdown.tsx       react-markdown, no raw HTML, links target=_blank rel=noopener
  serverChat.ts      transport factory: own fetch, sends {conversationId, message, targetSlug} only
  drafts.ts          lastDraft(messages) → {html, targetSlug} | null  (pure, tested)
  chat.css           sc-chat-* classes; colours via CSS variables with fallbacks (--sc-chat-accent, …)
  index.ts           public exports
```

`ChatPanel` props:

| Prop | Meaning |
|---|---|
| `endpoint` | `'/api/chat'` (joined to the API base by the given `fetch`) |
| `fetch` | token-carrying `fetch`; throws for non-OK with a readable message |
| `conversationId?`, `initialMessages?` | continue a saved conversation |
| `targetSlug?` | rework: sent with the first message of a new conversation |
| `greeting?`, `placeholder?` | texts (platform UI is English) |
| `onDraft(draft)` | `data-draft` → the page's preview |
| `onConversation({id, title})` | first answer of a new conversation → the page updates the URL and the list |

- Rendering: one component per part type at **module level** (the assistant-ui freeze lesson), `useChat` with
  `throttle: 50`. `step-start` → nothing; text → Markdown; `tool-save_draft` → chip "Saved the app (12 KB)" /
  error; `tool-get_app` → chip "Read the app lunch-poll"; other tools → "name: done"; `data-draft` → a one-line
  "Draft updated — see the preview".
- **Working indicator** for the silent minute: while `status` is `submitted` or `streaming`, a line at the bottom:
  "Thinking…" before the first text, then "Working… 23 s" (seconds since the last part arrived; shows that
  the stream is alive even when no text comes).
- Stop button replaces Send while busy → `stop()`. Errors: `error.message` under the messages + "Try again"
  (`clearError`, then the user re-sends).
- Enter sends, Shift+Enter new line (as today). Scroll to bottom on new parts unless the user scrolled up.
- Boundary: `src/chat/boundary.test.ts` reads every file in the folder and fails on imports starting with `../`
  (only `react`, `ai`, `@ai-sdk/react`, `react-markdown` and `./` allowed).

## 2.2. GUI side

- `api/client.ts`: export `authorizedFetch(input, init)` — API base + bearer, 401 → sign-out flow, non-OK →
  `ApiError` with the server's `{code, message}` (same rules as `send`, which it then shares).
- `api/queries.ts`: remove the mock `useChat`; add `useConversations()` (`GET /api/chat/conversations`),
  `useConversation(id)` (`GET …/{id}`, `staleTime: Infinity` — the panel owns the live state), `usePlatform()`.
- `BuildPage` (route `/build`), three areas:
  - left: **conversation list** ("New app" button + items: title, "changes *app*" when `targetSlug`, relative
    date); collapsible below 1100 px.
  - middle: `ChatPanel` keyed by the conversation (`?c=<id>`), or a new one (`?app=<slug>` for rework).
  - right: preview — `DraftFrame` with the draft + a note "The preview has no data — data works after Deploy."
    (U1); bar: new app → `DeployBar` (name + Deploy, as today); rework → **"Replace HTML of <name>"** (reusing
    `useReplaceHtml`), then `/apps/<slug>` (U3).
  - On `onConversation`: URL `?c=<id>` (replace, no new history entry), invalidate the list.
  - Opening `?c=<id>`: load messages, preview = `lastDraft(messages)`.
- `AppManageMenu`: builders get **"Change with AI"** → `/build?app=<slug>` (U3).
- Removed (`types.ts` Chat types, `ChatMessage` usage) with the mock.

## 2.3. `/_lib/` in previews (the known gap)
- Backend: `AppOrigin.libraryBaseUrl()` = the root of the apps origin (path mode: `https://apps-host/`; host mode:
  the root of `urlFor("lib")` — every apps host serves `/_lib/`). `GET /api/platform` (signed in) →
  `{ "libraryBaseUrl": "…/" }` in a new `PlatformController`.
- `DraftFrame` gets `baseUrl?`: inserts `<base href="…">` right after `<head>` (or at the start when there is no
  head) of the **srcdoc copy only** — the deployed HTML is untouched (its CSP has `base-uri 'none'`).
  Isolation unchanged: still `srcdoc`, still no `allow-same-origin`. Upload's preview gets it too.
- Side effect: in-page `#anchor` links in a draft navigate the frame to the apps origin (harmless, sandboxed);
  noted in `app-origin.md`.

## 2.4. Removing the mock (U4)
- Backend: `controller/ChatController`, `service/MockChatService`, `model/ChatMessage|ChatRequest|ChatResponse`,
  `sample-apps/draft.html`, `ChatControllerIT`. `AGENTS.md`/`chat-protocol.md` lose the mock rows.

## 2.5. Dependencies (exact versions, all older than two weeks)
`ai@7.0.146`, `@ai-sdk/react@4.0.150`, `react-markdown@10.1.0` (peer React ≥ 18). Bundle: ≈ +47 KB gzip for the SDK
(measured in SHC-0006) + ≈ 30 KB for markdown; checked with `vite build` after Task 3.

# 3. Decisions for the owner

| # | Question | Proposal |
|---|---|---|
| D1 | Apps origin for the preview | `GET /api/platform` → `libraryBaseUrl`; fixes unstyled drafts in Build and Upload |
| D2 | Layout | Three columns: conversations · chat · preview; list collapses on narrow screens |
| D3 | Markdown | `react-markdown` (safe by default, ~30 KB) rather than a hand-written subset |
| D4 | Silent minute | "Working… N s" counter while the answer runs without new parts |

# 4. Trade-offs
- `DefaultChatTransport` + own `fetch` instead of an own transport: the SDK keeps parsing and state; our code only
  shapes the request and the errors.
- Messages restored from the server are passed as `initialMessages`; the live `useChat` state is the truth until
  the panel is remounted — no syncing between the query cache and the chat.
- Vitest runs without a DOM here (jsdom was dropped for the bridge tests), so React components are not unit-tested;
  logic lives in pure modules (`drafts.ts`, `serverChat.ts` request shaping, base-href insertion, error mapping)
  that are. The screen itself is checked in the browser.

# 5. Phase 3 tasks

- Task 1 — Backend: `AppOrigin.libraryBaseUrl()`, `PlatformController` `GET /api/platform`; remove the mock
  (`ChatController`, `MockChatService`, `model/Chat*`, `draft.html`, `ChatControllerIT`)
- Task 2 — `api/client.ts` `authorizedFetch` (shared with `send`); `api/queries.ts`: conversations, conversation,
  platform; mock query and types removed
- Task 3 — Dependencies `ai`, `@ai-sdk/react`, `react-markdown` (exact versions); bundle size noted
- Task 4 — `src/chat/`: `serverChat.ts`, `drafts.ts`, `Markdown.tsx`, `parts.tsx`, `ChatPanel.tsx`, `chat.css`,
  `index.ts`
- Task 5 — `DraftFrame` `baseUrl` (+ Upload uses it); preview note
- Task 6 — `BuildPage`: conversation list, keyed panel, URL state, Deploy vs. Replace HTML
- Task 7 — `AppManageMenu`: "Change with AI"
- Task 8 — Docs: `chat-protocol.md` (client side), `app-origin.md` (gap closed), `AGENTS.md` (layout, API)
- Task 9 — Check in the browser locally (layout, errors without a key), deploy on the owner's order, check on `test`

Phase 4 (planned): `src/chat/boundary.test.ts`, `drafts.test.ts`, `serverChat.test.ts` (request body: only id +
new message + targetSlug on the first), base-href insertion test, `authorizedFetch` error mapping test;
backend `AppOriginTest` (library base in both modes), `PlatformControllerIT`.
