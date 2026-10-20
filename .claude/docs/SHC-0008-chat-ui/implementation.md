# Implementation — SHC-0008: React chat package — the Build screen on the real chat

Phase 3 approved with D1–D4 as proposed ("go, no gates for phase 3").

## Task 1 — Backend
- `AppOrigin.libraryBaseUrl()` (= `urlFor("lib")` resolved to `/`: path mode → apps host root, host mode →
  `lib.<suffix>`), `controller/PlatformController` `GET /api/platform` → `model/Platform {libraryBaseUrl}`.
- Removed the mock: `ChatController`, `MockChatService`, `model/ChatMessage|ChatRequest|ChatResponse`,
  `sample-apps/draft.html`, `ChatControllerIT`.

## Task 2 — API layer
- `api/client.ts`: `authorizedFetch` (API base, bearer, 401 → sign-in, non-OK → `ApiError`); the error parsing is
  shared with `send` (`failure()`).
- `api/queries.ts`: `usePlatform`, `useConversations`, `useConversation(id)` (read once), `conversationsKey`; the mock
  `useChat` and `types.ts` Chat types removed; `ConversationSummary`, `Platform` added.

## Task 3 — Dependencies
- `ai@7.0.146`, `@ai-sdk/react@4.0.150`, `react-markdown@10.1.0`, saved exact. GUI bundle 148 → 237 KB gzip.

## Task 4 — `src/chat/`
- `serverChat.ts` (`requestBody`, `serverTransport`), `drafts.ts` (`draftOf`, `lastDraft`), `toolLabels.ts`
  (`toolLine`), `Markdown.tsx`, `parts.tsx` (module-level, memoised `MessageView`), `ChatPanel.tsx` (callbacks via
  refs, Stop, quiet-seconds indicator, follow-bottom scrolling, Dismiss on error), `chat.css`, `index.ts`.
- Deviation: tool line texts in a pure module (`toolLabels.ts`) so they are unit-tested without a DOM.

## Task 5 — Preview
- `shell/draftHtml.ts#withBaseHref`; `DraftFrame` reads `/api/platform` itself — Build, Upload and the Replace
  dialog all get the fix without changes at the call sites. Note above the Build preview: no data until deployed.

## Task 6 — Build page
- `BuildPage`: conversation list (New app + items), `ChatPanel` keyed by session, `?c=` / `?app=` URL state with
  `adoptedId` (no remount when the server names the new chat), preview = live draft or the conversation's last one;
  `ReplaceBar` for rework, `DeployBar` otherwise. CSS: three columns; list becomes a row under 1100 px.

## Task 7 — Manage menu
- "✨ Change with AI" (builders) → `/build?app=<slug>`.

## Task 8 — Docs
- `chat-protocol.md` "Client implementation"; `app-origin.md` (gap closed); `data-api.md` (drafts without data by
  decision); `AGENTS.md` (layout, `/api/platform`, mock row removed).

## Task 9 — Checks
- `mvn verify`: 108 unit + 161 IT green; `npm test`: 52 green; `npm run build` clean (only Vite's >500 KB chunk
  notice).
- Local browser check: dev servers started (`:8080`, `:3000`), but the Chrome extension failed ("No group with id")
  twice — left to the owner (locally or on `test` after deploy).

# Phase 4 — Tests
- added (frontend): `chat/boundary.test.ts`, `chat/drafts.test.ts`, `chat/serverChat.test.ts`,
  `chat/toolLabels.test.ts`, `shell/draftHtml.test.ts`
- added (backend): `controller/PlatformControllerIT`, `AppOriginTest#libraryBaseUrl_isTheRootOfTheAppsOrigin_inBothModes`
- removed: `controller/ChatControllerIT` (mock)
- not covered by unit tests (no DOM in Vitest here): `ChatPanel`, `BuildPage` — checked in the browser
