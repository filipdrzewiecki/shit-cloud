# SHC-0010 — Dependency log + cross-space reads — Roadmap

Flow: **full** (one file per phase, phase gates mandatory).

- [x] Phase 1 — Analysis → `analysis.md` (U1 `space().find` only; U2 reader header; U3 row per collection + own use;
  U4 hourly refresh; U5 90 days / on delete; U6 chat + "Used by", not the delete dialog; U7 archived readable;
  U8 chat warns and builds readers)
- [x] Phase 2 — Solution design → `solution-design.md`
- [ ] Phase 3 — Implementation → `implementation.md`
    - [x] Task 1 — `V4__deps.sql` + `DependencyLog` + `data.deps-ttl`
    - [x] Task 2 — `DataController` records the reader; CORS header
    - [x] Task 3 — `ExpiryJob` removes stale rows
    - [x] Task 4 — `GET /api/deps/{space}`
    - [x] Task 5 — SDK v3 `space().find`; bridge; reader header
    - [x] Task 6 — "Used by" on the app page
    - [x] Task 7 — Chat: `get_deps`, prompt, guide
    - [x] Task 8 — Docs
    - [ ] Task 9 — Deploy (owner's order) + check on `test`
- [x] Phase 4 — Tests (written before deploy, not run)
    - added: `DependencyLogIT`, `DepsControllerIT`, `GetDepsToolTest`, `usedBy.test.ts`
    - modified: `DataControllerIT`, `ExpiryJobIT`, `SecurityIT`, `appBridge.test.ts`, `sdk.test.ts`, `toolLabels.test.ts`
- [ ] Close ticket (ask)
