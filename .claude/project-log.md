# Project log

What each closed ticket delivered, newest first. Durable details live in the files each entry points to.

## SHC-0012 — History browsing & restore (closed 2026-10-22)

- Builders open Manage → "Data & history" (`/apps/:slug/data`): collections with current and removed counts, records
  newest change first (paged, id-prefix filter, "Removed records"), a record's earlier states with fields marked
  against now, and "Restore this version" (inline confirm).
- Restore is a new write: shared space lock, version guard (409 if changed since, the page reloads), the replaced
  state goes to history as `restore` (`V6__restore_op.sql`); a removed record comes back at history's highest
  version + 1. History rows are addressed by row id — Data API re-creates restart versions at 1.
- Owner (question by question): own page, builders only, single records, restore with guard, raw JSON with marked
  differences, no change feed, no hand editing. "Versions" = states of one stored record, not app HTML versions.
- Tests written, not run by me; the owner's `mvn verify` and check on `test` are open.
- Reference: `.claude/docs/data-api.md` ("Data & history page"), `.claude/AGENTS.md`.

## SHC-0011 — AI data migration (closed 2026-10-22)

- The build chat converts a reworked app's stored documents: `propose_migration` with `function migrate(doc,
  collection)` is checked over every document in a GraalJS sandbox (CONSTRAINED, no Java/IO, statement limit per
  document, 60 s per run) and shown as a card (counts, field changes, before/after, failures, readers).
- Applied with Replace HTML in one transaction — in place, not into a new space (space = slug); runs the transform
  again on current data, all or nothing; old documents copied for 30 days (`V5__migrations.sql`: `migrations` audit +
  `migration_docs`); history ops `migrate`/`undo`.
- Undo (latest change, while the copy exists) puts back documents and HTML; download of the copy; `ExpiryJob` drops
  copies and unapplied proposals. Every data write takes a shared per-space advisory lock; apply/undo the exclusive.
- Chat package gained `onData`/`renderData`; `chat.max-steps` 5 → 8. Owner accepted all recommendations (Q1–Q8).
- Tests written, not run by me; the owner's `mvn verify` and check on `test` are open.
- Reference: `.claude/docs/data-migrations.md`, `.claude/docs/postgres.md`, `.claude/docs/chat-protocol.md`,
  `.claude/AGENTS.md`.

## SHC-0010 — Dependency log + cross-space reads (closed 2026-10-21)

- Apps read other apps' data with `shitcloud.db.space(slug).find(...)` (SDK 3): no writers on it, and the bridge
  honours a message's `space` for `find` only. Any existing app is readable, archived included.
- Dependency log `app_deps` (`V4__deps.sql`): the host page names the app on screen in `X-ShitCloud-App` (CORS
  allowed); each successful data call records reader, space, collection, read/write — own use too; refreshed at
  most hourly, best effort; rows go with either app or after `data.deps-ttl` (90 d). Self-declared → informs only.
- Shown as "Its data is used by …" on the app page for builders (`GET /api/deps/{space}`) and in the chat's
  `get_deps`, which the prompt calls before changing a data shape (warns, does not block). Not in the Delete dialog
  (owner). The chat can now build apps over other apps' data (`list_spaces` + `sample_docs` first).
- Live with `978aa3d`; tests written before deploy. The owner's check on `test` is still open
  (`.claude/docs/product-roadmap.md`).
- Reference: `.claude/docs/data-api.md`, `.claude/docs/chat-protocol.md`, `.claude/docs/app-management.md`,
  `.claude/docs/postgres.md`.

## SHC-0009 — ShitCloud tools + system prompt (closed 2026-10-21)

- The build chat edits apps in place: `edit_draft` (exact-text replacements on the latest draft, or the deployed app
  before the first draft; all or none; a miss says which edit and why). Owner's addition — departs from handoff
  §7.1's "whole file every time" after a team-calendar rewrite ran past the 60 s client timeout.
- `list_spaces` (non-archived apps, collections, counts) and `sample_docs` (≤ 5 newest documents, ~2 KB each) let
  the model keep existing data shapes; real documents go to the vendor (fine on `test`, approval before a pilot).
- Generic hook in `chat/`: `DataParts` via `TurnContext.data()` — a tool reads the latest data part.
- Prompt: `save_draft` for the first version, `edit_draft` for every change, look at data before touching it; apps
  always in English (also in the builder guide), chat replies in the user's language.
- Live with `604060a`. Closed on the owner's order before the acceptance run (listed under SHC-0009 in
  `.claude/docs/product-roadmap.md`); the tests were written right after closing.
- Reference: `.claude/docs/chat-protocol.md` ("ShitCloud's tools"), `.claude/docs/data-api.md`.

## SHC-0019 — Manage a deployed app (closed 2026-10-20)

- Builders archive (hidden, data kept, still opens with a Restore bar), restore, replace an app's HTML in place, and
  delete an app with all its data and history (typed name + `?confirm=<slug>`); everyone signed in downloads the
  HTML (`<name>.html`) and the data (`<slug>-data.json`, capped at 100 000 documents). Flyway `V2__archive.sql`.
- Opening no longer un-archives; a deploy by name does. GUI: "⋯ Manage" menu on the host page, Restore on archived
  cards.
- Went live with `0200cad` (the earlier push `4501a13` never deployed during the Railway outage); checked by the
  owner on `test`.
- Reference: `.claude/docs/app-management.md`.

## SHC-0007 — Chat backend (closed 2026-10-20)

- `POST /api/chat` streams the build chat from Claude (Spring AI 2.0.1, Sonnet 5.5) as the AI SDK UI message stream
  v1 over SSE; `GET /api/chat/conversations[/{id}]` returns a builder's own saved conversations as `UIMessage[]`.
- Own step loop over `ChatModel.stream()` (text deltas, then tool calls shown, run and fed to the next step); tools
  `save_draft` (whole app → `data-draft`) and `get_app` (rework, source to the model only). Stop cancels the model
  call; partial answers and token usage are saved (Flyway `V3__chat.sql`).
- Generic `chat` package (copyable; a test forbids other ShitCloud imports) + `buildchat` (endpoints, tools,
  prompt). System prompt = chat rules + the builder guide from `frontend/src/guide` (copied in by pom + Dockerfile).
- Found: Spring AI's Anthropic model hides tool-input streaming (the "silent minute" while an app is written —
  mitigated by a sentence before `save_draft` + pings) and ignores generic options (start from the model's own).
- The Build screen still uses the mock, now at `/api/chat/mock`, until the React chat package.
- 107 unit + 161 IT + 38 frontend tests; stream checked with `ai`'s own reader; verified live on `test` by the owner.
- Railway outage on 2026-10-20 delayed deploys: SHC-0019, SHC-0020 and this ticket went live together (`0200cad`).
- Reference: `.claude/docs/chat-protocol.md` (protocol, server implementation, check snippet), `.claude/AGENTS.md`.

## SHC-0020 — Upgrade to Spring Boot 4 (closed 2026-10-20)

- Boot 3.4 → 4.1.1 (Framework 7, Security 7, Jackson 3, Tomcat 11, Flyway 12, JUnit 6) so the chat can use
  Spring AI 2.0. Renamed starters, one test starter per technology, `spring-boot-starter-flyway`.
- Code: Jackson imports to `tools.jackson.*`, Tomcat factory to `org.springframework.boot.tomcat.servlet`
  (`addAdditionalConnectors`), `@AutoConfigureMockMvc` to `org.springframework.boot.webmvc.test.autoconfigure`.
  The properties migrator found `server.servlet.encoding.*` → `spring.servlet.encoding.*` (without it responses
  lose UTF-8); it was removed afterwards.
- Same API output as Boot 3 on captured requests, except the error `timestamp` format (`Z`) and health `groups`.
  94 unit + 145 IT + 38 frontend tests green, no test expectations changed.
- Live on `test` since `0200cad` (after a Railway outage): clean Boot 4 start, Flyway applied V2 and V3.
- Reference: `.claude/AGENTS.md` (stack), `.claude/docs/postgres.md` (Flyway starter).

## SHC-0006 — Chat library spike (closed 2026-10-20)

- Decided: the build chat uses Vercel AI SDK UI (`useChat`) with our own components, and the Spring endpoint emits
  the AI SDK UI message stream v1 (SSE) by hand — protocol specified event by event, with a captured example.
- Proven with throwaway prototypes: a plain-Java emitter parsed correctly by the SDK's own client; assistant-ui,
  `useChat` and a fully own UI working in Chrome on the same stream (tool step, markdown, draft to the preview with
  the rework target, Stop cancelling the server). Scored B 48 / own 49 / assistant-ui 43 / deep-chat 35; `useChat`
  chosen for the public protocol and the features it brings for +47 KB.
- Owner decisions: Spring Boot 4 first (new ticket SHC-0020), conversations saved on the server, chat creates and
  reworks apps, React package now / web component later. `ANTHROPIC_API_KEY` set on Railway `api` (owner's own key).
- Reference: `.claude/docs/chat-protocol.md`.

## SHC-0005 — Presence calendar end-to-end (closed 2026-10-20)

- Accepted without new code: the owner's Team Calendar (people, teams, vacation and office days per quarter,
  invoicing caps with a mute, integration budget per team, explicit Save) runs on `test` for several users on the
  real SDK, bridge and Data API, each change recorded with `updatedBy` and history. **Phase 1 (Skeleton) is done.**
- The app lives outside the repo (uploaded through ShitCloud); it falls back to browser storage when opened
  outside ShitCloud.

## SHC-0018 — Switch from MongoDB to PostgreSQL (closed 2026-10-20)

- Owner's decision: PostgreSQL replaces MongoDB everywhere. Tables from a Flyway migration (`apps`, `app_versions`,
  `docs` with JSONB `data`, `doc_history`), plain SQL through `JdbcClient`, a nightly `ExpiryJob` instead of TTL
  indexes. `test` started fresh (no data migration).
- Apps kept their Mongo-style filters; `FilterTranslator` turns them into parameterised SQL with MongoDB's meaning
  (array contains, null = missing, type-bracketed ranges by code point, Mongo sort order). Writes lock the row, so
  the retry loop is gone. Builder guide explains how values compare.
- Local dev and tests on an embedded PostgreSQL 18 (zonky), matching Railway's `Postgres` (18.6, TCP proxy on).
  Railway set up through the Railway tools: service, proxy, `api` variables.
- Incident after go-live: a JDBC `stream()` leaked one connection per data query and exhausted the pool of 10
  (500s). Fixed with `list()`; tests now run behind a 4-connection pool so leaks fail fast.
- 94 unit + 122 integration + 31 frontend tests green; verified live by the owner (lunch-poll, Team Calendar with
  integration budget and explicit Save).
- Left on Railway: the old `Mongo Single Replica` service + volume and `MONGODB_URI` on `api` (unused) — remove
  when the owner confirms.
- Reference: `.claude/docs/postgres.md` (replaces `mongodb.md`), `.claude/docs/data-api.md`.

## SHC-0004 — Data API + bridge (closed 2026-10-19)

- Apps have shared, durable data: `docs` (space = app slug, app fields under `data`) with every replaced or deleted
  state in `_history`, written in the same transaction; version-conditional writes, retried on collision.
- Data API (`/api/data/{space}/{collection}`: query, set, add, remove) for everyone signed in, guests included;
  Mongo-style filters rebuilt from an operator whitelist; `{code, message}` errors; size and name limits.
- SDK v2 (`shitcloud.me()`, `db.find/set/add/remove`) talks to a bridge in the host page that accepts only its own
  frame on the apps origin and takes the space from the route. Last write wins unless `expectedVersion` is given.
- Vitest added to the frontend (bridge + SDK); the dev sample `lunch-poll` votes through the SDK.
- 89 unit + 104 integration + 31 frontend tests green. Closed on the user's order before the live two-user check on
  `test`; local checks were done with curl (the Chrome extension did not respond).
- Reference: `.claude/docs/data-api.md`.

## SHC-0003 — App serving on its own origin (closed 2026-10-19)

- Apps run on their own origin: the API's second port (8081) with its own Railway domain
  (`api-test-e5c7.up.railway.app`), fenced from the API in both directions (`PortFence`), own security chain
  with framing allowed. `APPS_URL_PATTERN` picks a shared apps origin (`test`) or one origin per app (host mode).
- App documents get the SDK as the first script, a CSP that blocks all network calls and lets only the GUI
  frame them, `no-store`. Library mirror `/_lib/` with Alpine, Tailwind browser build, Chart.js.
- GUI frames deployed apps from `frameUrl` with `allow-same-origin`; drafts stay on opaque `srcdoc` frames.
- The local end-to-end run caught two bugs tests had missed (`/actuator` reachable on the apps port, a 500 from
  a record projection) — slugs `api`/`actuator`/`error` are now reserved.
- 50 unit + 80 integration tests green; verified live by the user (origin, storage, CSP blocks, framing refused,
  libraries). Known gap: draft previews can't load `/_lib/` yet (planned with the chat package).
- Reference: `.claude/docs/app-origin.md`.

## SHC-0002 — MongoDB + persisted app registry (closed 2026-10-18)

- MongoDB 7 single-node replica set `rs0` on Railway (template `Mongo Single Replica`, private network for the
  API; a TCP proxy kept on purpose to inspect `test`). The API requires `MONGODB_URI`.
- The in-memory `AppStore` is replaced by `AppRegistry`: apps live in `_apps`, every deploy is kept in
  `_app_versions` (90-day TTL), written in one transaction. The creator stays owner, each version has its author.
  A concurrent deploy gets 409, over 5 MB gets 413, and the GUI shows both. Indexes are created at startup.
- Local dev and tests need no install: an embedded `mongod` replica set (test scope) via `DevApplication`;
  sample apps are seeded in the dev profile only.
- 21 unit + 48 integration tests green; deployed to `test` and verified by the user, including in Compass.
- Process: the wiki and frontend-instruction phases were dropped from the SDLC flow (frontend is always in
  scope, docs happen on close). New critical rule: never commit or push without an explicit order.
- Reference: `.claude/docs/mongodb.md` (removed with SHC-0018; see `postgres.md`).

## SHC-0001 — Auth0 login + roles (closed 2026-10-18)

- Two-tier sign-in: builders/admins through Auth0 (shared my-intelligence tenant, roles `ShitCloud Builder` /
  `ShitCloud Admin`), everyone else with a shared password and a 1-day API-issued guest token.
- API is a JWT resource server accepting both issuers; every controller declares `@PreAuthorize`; deploy and
  chat are builder-only; `/api/me` and app `owner` come from the token.
- Guest tier built for long-term use: constant-time check, per-IP attempt limiter (429), password rotation
  signs all guests out, fails closed when unset.
- GUI: `/sign-in` front door, builders-only screens, sign-out, readable error for rejected builder sign-ins.
- Deployed to `test` and verified for both tiers; unit + integration tests green (`mvn verify`).
- Reference: `.claude/docs/auth.md`.
