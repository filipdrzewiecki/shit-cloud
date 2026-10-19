# Project log

What each closed ticket delivered, newest first. Durable details live in the files each entry points to.

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
- Reference: `.claude/docs/mongodb.md`.

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
