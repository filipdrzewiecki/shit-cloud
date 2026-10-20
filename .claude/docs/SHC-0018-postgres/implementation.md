# Implementation — SHC-0018: Switch from MongoDB to PostgreSQL (JSONB)

Input: `solution-design.md` (D1–D17, A1).

## Done (Tasks 1–9)

| Task | Result |
|---|---|
| 1 | `pom.xml`: Mongo starter + flapdoodle out; `spring-boot-starter-jdbc`, `postgresql` (runtime), `flyway-core` + `flyway-database-postgresql`, test `io.zonky.test:embedded-postgres` 2.2.2 with binaries BOM **18.6.0** (Railway's `:latest` = 18). Properties: `DATABASE_JDBC_URL/USER/PASSWORD`, no defaults |
| 2 | `db/migration/V1__schema.sql`: `apps`, `app_versions`, `docs` (jsonb), `doc_history`, FKs, indexes |
| 3 | `EmbeddedPostgresConfig` (+ `embedded-postgres.properties` for the placeholders), `IntegrationTest` truncates; Mongo test config removed |
| 4 | `AppRegistry` on `JdbcClient` (upsert `ON CONFLICT … version + 1 RETURNING *`), `AppRecord`/`AppVersion` plain records (`AppVersion.appSlug`), `SampleAppSeeder`; `db/Timestamps` (driver wants `OffsetDateTime`) |
| 5 | `FilterTranslator` → `Sql(text, params)`: whitelist unchanged; templates with `{F}`/`?`; equality with array-contains, null = missing, `$ne`/`$nin` match missing, type-bracketed ranges via `CASE` (strings `COLLATE "C"`), `_updatedAt` as timestamp, Mongo sort order |
| 6 | `DataService`: `FOR UPDATE` + history + update/delete in one transaction, `ON CONFLICT DO NOTHING` insert race, retry loop removed, ids = 8 hex seconds + 16 random hex |
| 7 | `db/ExpiryJob` (`@Scheduled`, `db.expiry-cron`, `purge(now)`), `@EnableScheduling`; `mongo/` deleted |
| 8 | Builder guide: how values compare (array contains, null, type-bracketed ranges, `_updatedAt` as ISO); `AGENTS.md` (stack, running, layout, API); new `.claude/docs/postgres.md`; `data-api.md` storage/testing |
| 9 | Local run on embedded PostgreSQL 18.6: Flyway V1 applied, samples seeded, apps list/open, app document on :8081, Data API set/version/history path, add (24-hex id), `$in` + sort, `$gt` + `_updatedBy`, SQL-injection value → `[]`, `VERSION_CONFLICT`, `$where` 400, `NO_SUCH_APP`, remove true/false |

## Deviations from the design

- **`{field: null}`** matches null *or missing* (MongoDB's meaning) — not in the §5 table, added while writing
  `FilterSqlIT`; `$ne: null` = present and not null.
- **Ranges use typed parameters** (text / numeric / boolean, chosen from the Java value) inside a `CASE`, not a
  `jsonb` comparison: JSONB orders strings by the database collation (locale-dependent), MongoDB by code point.
  Range on an object/array/null value → 400.
- **`_updatedAt`** compares as a timestamp (ISO text parsed by Postgres); `$in`/`$nin` on it → 400.
- An empty `{}` inside `$and`/`$or` means `true` (the builder guide's paging snippet produces it).
- Flyway 10 warns that PostgreSQL 18 is newer than it has tested — harmless (`postgres.md`).
- Tests run on the user's go: 94 unit + 121 integration + 31 frontend, all green on the first run.

## Task 10 — Railway (done through the Railway tools, on the user's go)
- `Postgres` service from the `postgres` template (`postgres-ssl:18`, PostgreSQL 18.6, region sfo like the rest).
- TCP proxy `shuttle.proxy.rlwy.net:38101` → 5432.
- `api` variables `DATABASE_JDBC_URL`, `DATABASE_USER`, `DATABASE_PASSWORD` as references to `Postgres`, set
  without redeploying (the running Mongo build ignores them).
- Still to do after the deploy is verified: remove `MONGODB_URI`, delete `Mongo Single Replica` + volume.

## Tests added / changed (Phase 4)
- New: `data/FilterSqlIT`, `data/DataServiceTest`, `db/SchemaIT` (replaces `MongoIndexesIT`), `db/ExpiryJobIT`
- Rewritten: `data/FilterTranslatorTest` (validation + "no app input in SQL text" + placeholder/parameter agreement)
- Ported: `AppRegistryIT` (rollback via `CHECK (false) NOT VALID`), `SampleAppSeederIT`, `DataServiceIT`,
  `AppDocumentIT`, `IntegrationTest`
- Unchanged: controller ITs, `PortFenceIT`, `LibraryMirrorIT`, auth ITs, frontend Vitest
