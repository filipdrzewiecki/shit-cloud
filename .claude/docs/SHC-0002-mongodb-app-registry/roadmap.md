# SHC-0002 — MongoDB + persisted app registry — Roadmap

Flow: **full** (one file per phase, phase gates mandatory).

- [x] Phase 1 — Analysis → `analysis.md`
- [x] Phase 2 — Solution Design → `solution-design.md` (U1 embedded Mongo, U2 Railway template, U3 seed in dev only, U4 creator stays owner)
- [~] Phase 3 — Implementation → `implementation.md`
    - [x] Task 1 — Mongo deps (flapdoodle test-scope), properties, `DevApplication`, `npm run dev`, `AppsProperties`
    - [x] Task 2 — `MongoConfig`, `MongoCollections`, `MongoIndexes`
    - [x] Task 3 — `AppRecord`, `AppVersion`, `AppRegistry`; `AppController` switched; `AppStore` deleted
    - [x] Task 4 — `SampleAppSeeder` (dev profile)
    - [x] Task 5 — Frontend: readable deploy errors (413 too large, 409 retry) in `DeployBar`
    - [x] Task 6 — AGENTS.md (stack, running, layout, Railway)
    - [x] Task 7 — Railway: Mongo template, private only, `MONGODB_URI` on `api`
    - [ ] Task 8 — Deploy to `test` (user pushes) + acceptance criteria
- [x] Phase 4 — Tests (mvn verify green: 21 unit + 48 IT; list in `implementation.md`)
- [ ] Close ticket (ask)
