# ShitCloud — Product Roadmap

Ticket-level plan derived from `shitcloud-handoff.md` §9 (delivery phases). Each ticket runs the full
SDLC flow (`.claude/instructions/sdlc-flow.md`) in `.claude/docs/SHC-XXXX-<name>/`. Closed tickets are
logged in `.claude/project-log.md`.

Ordering rule: every ticket ends **deployed on Railway `test`** (baby steps — each one is a usable
increment, never a half-built layer). Tickets are re-ordered or split freely; the "done when"
criteria per handoff phase are what matter.

Legend: `[x]` done · `[~]` in progress · `[ ]` not started

---

## Phase 0 — Groundwork (done)

- [x] Monorepo skeleton copied from my-intelligence (Spring Boot API + React/nginx GUI)
- [x] Railway project `shitcloud` / env `test`, two services auto-deployed from `main`
- [x] GUI mockup on in-memory data: app list, host page + sandboxed iframe, build chat (mock), upload
- [x] Agent rules + SDLC flow ported into `.claude/`

## Phase 1 — Skeleton

**Done when:** a hand-written presence calendar works for two logged-in users (handoff §9.1).

- [x] **SHC-0001 — Auth0 login + roles.** SPA login (PKCE), API as JWT resource server, user /
  builder / admin roles from a token claim, `/api/me` from the token, deploy restricted to builders.
- [x] **SHC-0002 — MongoDB + persisted app registry.** Mongo on Railway as single-node replica set
  (`rs0`), `MongoTemplate`, `_apps` + `_app_versions` (TTL) replace the in-memory `AppStore`;
  index creation at startup. Local dev story (container or embedded).
- [x] **SHC-0003 — App serving on its own origin.** Resolve the Railway wildcard-domain question
  (fallback: one shared apps domain on test). Serve app HTML from the apps origin with the SDK injected
  as the first `<script>`, CSP `connect-src 'none'`, sandbox incl. `allow-same-origin`. Minimal local
  library mirror (Alpine, Tailwind, Chart.js) so CSP doesn't break apps.
- [x] **SHC-0004 — Data API + bridge.** `docs` collection, whitelist filter → `Criteria`, upsert /
  delete with `_history` in the same transaction, optimistic `version`; host-page `postMessage`
  bridge with origin/source checks; `window.shitcloud.db` + `me()` in the SDK.
- [x] **SHC-0018 — Switch from MongoDB to PostgreSQL (JSONB).** Owner's decision: Postgres replaces Mongo
  everywhere (registry tables, app data as JSONB, Flyway, scheduled expiry, embedded Postgres locally); the SDK's
  filter syntax stays. Numbered last, done before SHC-0005.
- [x] **SHC-0019 — Manage a deployed app: remove, download, replace HTML.** Owner's request (2026-10-20):
  1. *Remove* an app in two ways: **HTML only** = the app is archived (hidden from the list, its data kept) and can
     be brought back later; **with its data** = the app, its versions and its documents are deleted (history
     follows its retention).
  2. *Download* an app: the HTML alone, or the HTML **with its data** (e.g. one file/zip with the documents as JSON).
  3. *Replace the HTML* of an existing app in place (pick the app, upload a new file) without touching its data —
     today only a deploy under the exact same name does that.
  Open for analysis: who may do each (builder / owner / admin), confirmation for the destructive remove, the
  download format (and whether re-uploading a "with data" download restores data), how archived apps are listed
  and restored. Takes over the "admin archive/delete" part of SHC-0014.
- [x] **SHC-0005 — Presence calendar end-to-end.** Hand-written calendar on the real SDK, two users,
  write-only-own-space rule, `updatedBy`. Phase 1 acceptance. *Accepted as is (2026-10-20): the owner's Team
  Calendar (people, teams, vacation/office days, invoicing caps, integration budget) runs on `test` for several
  users through the SDK, bridge and Data API; no separate app built.*

## Phase 2 — Chat

**Done when:** 4 of 5 different apps generated from a prompt work without manual fixes.

- [x] **SHC-0006 — Chat library spike.** Evaluate assistant-ui / Vercel AI SDK UI vs. own; pick the
  streaming protocol the Spring endpoint must emit. Output: decision record, no production code.
  *Decided: `useChat` (AI SDK UI) + own components; AI SDK UI message stream v1 from Spring — `.claude/docs/chat-protocol.md`.*
- [x] **SHC-0020 — Upgrade to Spring Boot 4.** Owner's decision in SHC-0006: Spring AI 2.0 (GA June 2026) needs
  Boot 4 / Spring Framework 7 / Jackson 3, so the platform moves first and the chat starts on the current line.
  Covers Boot 4.x, Jackson 3 package changes, Spring Security 7, Flyway, test setup; all tests green, deploy on `test`.
  *Done on Boot 4.1.1; live on `test`.*
- [x] **SHC-0007 — Generic chat module (backend).** Own package, easy to copy (owner, SHC-0007 U1): conversation, SSE
  streaming, pluggable tool registration, Spring AI 2.0 + Anthropic, key from env. Conversations saved on the
  server per builder with their drafts (SHC-0006 decision); chat both creates and reworks apps.
  *Done: `POST /api/chat` streams from Claude Sonnet 5.5 with `save_draft` + `get_app`; Build screen still on the mock.*
- [~] **SHC-0008 — Reusable React chat package.** Separate package (also as web component), used by
  the build screen; live preview in the same sandbox/origin model as production. Includes the
  draft-preview fix: `DraftFrame` gets `<base href>` to the apps origin so `/_lib/` loads in previews
  (`.claude/docs/app-origin.md`, known gap) — pull it forward if Upload previews hurt before then.
- [x] **SHC-0009 — ShitCloud tools + system prompt.** `list_spaces`, `sample_docs`, `get_app`,
  `save_draft`; SDK docs in the system prompt; iterate on prompt quality against the 4/5 criterion.
  *Closed on the owner's order (2026-10-21) with `edit_draft` added; the tools are live with `604060a`. Tests added after
  closing (not yet run by the owner): `DataPartsTest`, `EditDraftToolTest`, `SampleDocsToolTest`,
  `BuildChatControllerIT` (edits on app → draft → next message, failed edit, data tools), `DataServiceIT`
  (`spaces`, `sample`), `toolLabels.test.ts`. **Still open:** the owner's acceptance run — five prompts, each in a
  new conversation on `test`, pass = works without manual fixes, Phase 2 done at 4/5:*
  1. *Lunch poll: today's options, one vote per person, live results*
  2. *Equipment loans: who has which laptop/monitor, return date, overdue in red*
  3. *Meeting-room booking: 3 rooms, 30-min slots, no double booking*
  4. *Retro board: 3 columns, cards, votes, chart of votes per column*
  5. *Change (Manage → Change with AI) team-calendar: add "Invoiced" per month, saved with Save — data survives,
     `edit_draft` used*

## Phase 3 — Floating data

**Done when:** the calendar is rewritten to a new data format without losing records, and an external
HTML with CDN libs + localStorage is imported and works.

- [~] **SHC-0010 — Dependency log + cross-space reads.** `_deps` upserts, `shitcloud.db.space()`
  read-only, `get_deps` tool, warnings in chat.
- [ ] **SHC-0011 — AI data migration.** `propose_migration`, GraalJS sandbox (no I/O), counts +
  before/after samples, human approval, old space read-only 30 days, `_migrations` audit.
- [ ] **SHC-0012 — History browsing & restore.** Builder panel: document history, restore.
- [ ] **SHC-0013 — Import from disk with AI adaptation.** Agent rewrites CDN/fetch/localStorage,
  change list, side-by-side before/after preview, size limit.

## Phase 4 — Polish & pilot

**Done when:** the pilot team built at least one app nobody asked them to build.

- [ ] **SHC-0014 — App lifecycle.** 30-day automatic archiving (manual archive/restore/delete came with SHC-0019;
  since then opening an archived app no longer restores it — auto-archived apps need Restore too, or a separate
  "auto" state decided here).
- [ ] **SHC-0015 — Token limits.** Daily limit per builder, usage counters, admin view.
- [ ] **SHC-0016 — ZIP upload + full library mirror.**
- [ ] **SHC-0017 — Backups & pilot readiness.** `mongodump` to a bucket, one tested restore, vendor
  approval checklist, two-week pilot.

## Phase 5 — Later

- RAG over documents (Mongo `mongot`, 8.2+)
- On-prem move: ADFS / Entra ID OIDC, company Mongo, wildcard DNS + TLS

---

## Open questions carried from the handoff (§12)

| Question | Decided in |
|---|---|
| Railway wildcard custom domains? Test fallback for per-app origins | SHC-0003 — shared apps domain on `test` (second generated domain → port 8081); per-app hosts are config only |
| AD flavour (ADFS vs Entra ID) | Phase 5 — roles claim kept configurable (`.claude/docs/auth.md`) |
| Does the company run MongoDB on-prem? | Phase 5 |
| React chat library + streaming protocol | SHC-0006 — `useChat` + own components, AI SDK UI message stream v1 (`chat-protocol.md`) |
| Library mirror contents | SHC-0003 (minimal: Alpine, Tailwind browser, Chart.js — done), SHC-0016 (full) |
| Neutral identifiers (package, domains, env vars) | Open — ticket prefix `SHC` chosen |
| LLM vendor key and approval | `test`: the owner's own Anthropic API key (added to Railway by the owner when SHC-0007 needs it); no company data goes into prompts. Company approval is still needed before a pilot with real data |
