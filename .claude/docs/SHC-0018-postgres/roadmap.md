# SHC-0018 — Switch from MongoDB to PostgreSQL (JSONB) — Roadmap

Flow: **full** (one file per phase, phase gates mandatory). Done before SHC-0005, so the calendar is built on
the final storage.

- [x] Phase 1 — Analysis → `analysis.md` (U1 start fresh, U2 keep the SDK's filter syntax, U3 embedded Postgres locally, U4 public TCP proxy on test)
- [x] Phase 2 — Solution Design → `solution-design.md` (D1–D17, A1 array equality = contains)
- [ ] Phase 3 — Implementation → `implementation.md`
    - [x] Task 1 — `pom.xml` (remove Mongo, add JDBC/Postgres/Flyway/zonky pinned to Railway's major), properties
    - [x] Task 2 — Flyway `V1__schema.sql`
    - [x] Task 3 — Test infra: `EmbeddedPostgresConfig`, `DevApplication`, `IntegrationTest` truncation
    - [x] Task 4 — `AppRegistry`, `AppRecord`/`AppVersion`, `SampleAppSeeder` on `JdbcClient`
    - [x] Task 5 — `FilterTranslator` → SQL over JSONB
    - [x] Task 6 — `DataService` on `JdbcClient` (row locks, history, ids)
    - [x] Task 7 — `ExpiryJob`; delete the `mongo/` package
    - [x] Task 8 — Builder guide note, `AGENTS.md`, draft `postgres.md`
    - [x] Task 9 — Local end-to-end check
    - [x] Task 10 — Railway (user, dashboard): Postgres, TCP proxy, `api` variables
    - [ ] Task 11 — Deploy (on the user's order) + acceptance; then remove Mongo (user)
- [x] Phase 4 — Tests: 94 unit + 121 integration + 31 frontend green (new: FilterSqlIT, SchemaIT, ExpiryJobIT, DataServiceTest)
- [ ] Close ticket (ask)
