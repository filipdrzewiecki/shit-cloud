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
- [ ] **SHC-0003 — App serving on its own origin.** Resolve the Railway wildcard-domain question
  (fallback: one shared apps domain on test). Serve app HTML from the apps origin with the SDK injected
  as the first `<script>`, CSP `connect-src 'none'`, sandbox incl. `allow-same-origin`. Minimal local
  library mirror (Alpine, Tailwind, Chart.js) so CSP doesn't break apps.
- [ ] **SHC-0004 — Data API + bridge.** `docs` collection, whitelist filter → `Criteria`, upsert /
  delete with `_history` in the same transaction, optimistic `version`; host-page `postMessage`
  bridge with origin/source checks; `window.shitcloud.db` + `me()` in the SDK.
- [ ] **SHC-0005 — Presence calendar end-to-end.** Hand-written calendar on the real SDK, two users,
  write-only-own-space rule, `updatedBy`. Phase 1 acceptance.

## Phase 2 — Chat

**Done when:** 4 of 5 different apps generated from a prompt work without manual fixes.

- [ ] **SHC-0006 — Chat library spike.** Evaluate assistant-ui / Vercel AI SDK UI vs. own; pick the
  streaming protocol the Spring endpoint must emit. Output: decision record, no production code.
- [ ] **SHC-0007 — Generic chat module (backend).** Separate Maven module: conversation, SSE
  streaming, pluggable tool registration, Spring AI + Anthropic, key from env.
- [ ] **SHC-0008 — Reusable React chat package.** Separate package (also as web component), used by
  the build screen; live preview in the same sandbox/origin model as production.
- [ ] **SHC-0009 — ShitCloud tools + system prompt.** `list_spaces`, `sample_docs`, `get_app`,
  `save_draft`; SDK docs in the system prompt; iterate on prompt quality against the 4/5 criterion.

## Phase 3 — Floating data

**Done when:** the calendar is rewritten to a new data format without losing records, and an external
HTML with CDN libs + localStorage is imported and works.

- [ ] **SHC-0010 — Dependency log + cross-space reads.** `_deps` upserts, `shitcloud.db.space()`
  read-only, `get_deps` tool, warnings in chat.
- [ ] **SHC-0011 — AI data migration.** `propose_migration`, GraalJS sandbox (no I/O), counts +
  before/after samples, human approval, old space read-only 30 days, `_migrations` audit.
- [ ] **SHC-0012 — History browsing & restore.** Builder panel: document history, restore.
- [ ] **SHC-0013 — Import from disk with AI adaptation.** Agent rewrites CDN/fetch/localStorage,
  change list, side-by-side before/after preview, size limit.

## Phase 4 — Polish & pilot

**Done when:** the pilot team built at least one app nobody asked them to build.

- [ ] **SHC-0014 — App lifecycle.** 30-day archiving, archived filter, admin archive/delete.
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
| Railway wildcard custom domains? Test fallback for per-app origins | SHC-0003 |
| AD flavour (ADFS vs Entra ID) | Phase 5 — roles claim kept configurable (`.claude/docs/auth.md`) |
| Does the company run MongoDB on-prem? | Phase 5 |
| React chat library + streaming protocol | SHC-0006 |
| Library mirror contents | SHC-0003 (minimal), SHC-0016 (full) |
| Neutral identifiers (package, domains, env vars) | Open — ticket prefix `SHC` chosen |
