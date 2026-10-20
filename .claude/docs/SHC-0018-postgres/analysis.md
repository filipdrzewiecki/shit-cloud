# Analysis — SHC-0018: Switch from MongoDB to PostgreSQL (JSONB)

## Metadata

| Field               | Value |
|---------------------|-------|
| **Description**     | Replace MongoDB with PostgreSQL as the only database. App data is stored as JSONB; the platform's own data in ordinary tables. The SDK, REST API, bridge and GUI do not change. |
| **Ticket**          | SHC-0018 (ordered before SHC-0005) |
| **Endpoints**       | unchanged — `/api/apps/**`, `/api/data/{space}/{collection}/**` |
| **Key classes**     | `AppRegistry`, `DataService`, `FilterTranslator`, `MongoIndexes` (goes), `SampleAppSeeder` |
| **Related classes** | `AppRecord`, `AppVersion`, `DocRecord`, `HistoryRecord`, `DevApplication`, `IntegrationTest`, all `*IT` touching data |
| **Infra**           | Railway: Postgres service replaces `Mongo Single Replica`; local/tests: embedded Postgres |
| **References**      | `.claude/docs/mongodb.md` (to be replaced), `.claude/docs/data-api.md`, `.claude/project-log.md` (SHC-0002, SHC-0004) |

---

# 1. Problem

## 1.1. What is the problem?
- The owner decided to move from MongoDB to PostgreSQL. MongoDB was chosen in the handoff to learn it; the
  decision is now reversed (company infrastructure, SQL familiarity, simpler operations).
- Everything persistent sits on Mongo today: the app registry (`_apps`, `_app_versions`), app data (`docs`,
  `_history`), indexes/TTLs (`MongoIndexes`), local dev and tests (embedded `mongod`), Railway (`Mongo Single
  Replica` + TCP proxy).

## 1.2. Why is it a problem? What may be problematic to implement?
- **Filter translation is the hard part.** Apps keep sending Mongo-style filters (U2); they must become
  parameterised SQL over JSONB with the same meaning, without ever building SQL from app input. Semantics that
  differ between Mongo and Postgres JSONB:
  - comparing values of different JSON types (Mongo orders by type then value; JSONB comparison operators do the
    same for `jsonb` values, plain text extraction `->>` would not),
  - missing field vs `null` (`$exists`, `$ne` matching documents without the field),
  - equality on an array field (Mongo `{tags: 'a'}` matches arrays containing `'a'`),
  - dotted paths into nested objects; sorting on JSON values.
- **No TTL indexes.** `_app_versions` and `_history` expire after 90 days in Mongo by themselves; Postgres needs a
  scheduled cleanup.
- **Schema management.** Tables and indexes must be created and evolved by migrations (not at startup by code),
  and work the same on embedded Postgres and Railway.
- **Concurrency model changes** (a simplification, but tests encode the old behaviour): Mongo transactions fail
  fast on write conflicts, hence the retry loop with back-off in `DataService`; Postgres row locks make concurrent
  writers wait instead.
- **Ids.** `db.add` returns Mongo `ObjectId` hex today; a replacement generator is needed (any format works —
  data starts fresh).
- **Local dev must stay zero-install** (U3), including on Windows.

## 1.3. How user would like to solve it
- PostgreSQL with **JSONB columns** for app data (as discussed earlier).
- **Start fresh** (U1): no data migration; test data and apps are re-uploaded.
- **Keep the SDK's Mongo-style filter syntax** (U2): apps, the builder guide and Team Calendar do not change.
- **Embedded Postgres** for `npm run dev` and tests (U3): nothing to install, no Docker.
- **Public TCP proxy** on Railway's Postgres (U4) to inspect `test` from a desktop SQL client, like Compass today.

### User decisions (Phase 1)
| # | Decision |
|---|---|
| U1 | Start fresh on `test` — no Mongo → Postgres data migration |
| U2 | Keep the SDK / REST filter syntax (`$eq … $or`, `sort`, `limit`); only the server translation changes |
| U3 | Embedded Postgres locally and in tests (test scope, like today's embedded `mongod`) |
| U4 | Public, password-protected TCP proxy on the Railway Postgres for inspection |

# 2. Context

## 2.1. Key context
- API contracts are stable and well tested: `AppControllerIT`, `DataControllerIT`, `DataServiceIT`,
  `FilterTranslatorTest`, `PortFenceIT`, `AppDocumentIT` describe the behaviour to keep. Tests that assert
  Mongo internals (`MongoIndexesIT`, index names, `_class`, the `collMod` validator trick) are rewritten.
- Rules that must survive: history in the same transaction as the write; one history entry per replaced state
  under concurrency; `expectedVersion` → `VERSION_CONFLICT`; last write wins otherwise; space = existing app;
  limits (256 KB, 1000 results, names/ids); `id`/`_` keys dropped; filters can never reach platform fields.
- Mongo leftovers to remove: `spring-boot-starter-data-mongodb`, flapdoodle, `mongo/` package,
  `embedded-mongo.properties`, `MONGODB_URI`, `_class` noise (disappears by itself).
- Railway: a Postgres service from Railway's database template provides `DATABASE_URL` (private) and a public
  URL through a TCP proxy. Mongo service, its volume and its TCP proxy are removed afterwards (U1: nothing to keep).

## 2.2. Main flows (unchanged from the outside)

```
SDK (app) → bridge (host page) → REST /api/data/... → DataService → FilterTranslator → SQL over JSONB → Postgres
GUI → REST /api/apps/... → AppRegistry → SQL → Postgres
```

## 2.3. Current state of things
- 14 main + 10 test files depend on Mongo (see the grep in this ticket's history); everything else (auth,
  apps origin, PortFence, SDK, bridge, frontend) is database-agnostic.

# 3. Options and open points for Phase 2

## 3.1. Data access
| Option | Pros | Cons |
|---|---|---|
| A. Spring `JdbcClient` + hand-written SQL | Exact control of JSONB SQL; mirrors today's `MongoTemplate` style; no ORM magic | Row mapping by hand |
| B. Spring Data JPA / Hibernate for `_apps`, JDBC for `docs` | Less code for the registry | Two styles; JSONB in JPA needs custom types; ORM surprises |
| C. jOOQ | Type-safe SQL | Code generation, new tool to learn |

Leaning **A** (one way to talk to the database, as with `MongoTemplate`).

## 3.2. Schema
- `apps` (slug PK, name, description, html, owner, version, last_used_at, archived, created_at, updated_at,
  updated_by), `app_versions` (unique app_id + version), `docs` (`data JSONB`, unique space + collection + doc_id),
  `doc_history`. Platform tables no longer need the `_` prefix (app data is a separate table, not a collection
  namespace) — Phase 2 decides names.
- Indexes: `docs (space, collection, doc_id)` unique; a **GIN index on `data`** for equality/containment
  filters; `apps (last_used_at desc)`; `doc_history (space, collection, doc_id, version)`; `created_at`/`changed_at`
  for cleanup.

## 3.3. Migrations
- **Flyway** (`db/migration/V1__…sql`), run by Spring Boot at startup, same on embedded and Railway. Plain SQL,
  readable in review. (Liquibase: heavier, XML/YAML.)

## 3.4. Filter → SQL
- Path `a.b` → `data #> '{a,b}'` (JSONB value, keeps type-aware comparison); values bound as `?::jsonb`.
- `$eq/$ne/$gt/$gte/$lt/$lte` → `=`, `IS DISTINCT FROM` (Mongo `$ne` also matches missing fields),
  `>`, `>=`, `<`, `<=` on JSONB; `$in/$nin` → `= ANY(?::jsonb[])` / `NOT (… )` with missing handling; `$exists` →
  `IS [NOT] NULL` on the path (JSON `null` is a value, a missing field is SQL `NULL`); `$and/$or` → parenthesised.
- `id`, `_updatedAt`, `_updatedBy` → real columns.
- **Array membership** (`{tags: 'a'}` matching `['a','b']`): Mongo does it implicitly. Options: support via
  `data @> '{"tags":["a"]}'`-style containment, or document as unsupported (exact match only). Phase 2.
- Sort on `data #> path` (JSONB ordering), then `created_at`, `id`.
- Every value is a bind parameter; field paths are validated against the existing whitelist before being turned
  into path arrays (also bound as parameters: `data #> ?::text[]`).

## 3.5. Concurrency and history
- `SELECT … FOR UPDATE` on the document inside the transaction serialises writers on one document: each sees the
  previous writer's committed state, writes exactly one history row, no retry loop needed. Insert races handled
  with `INSERT … ON CONFLICT DO NOTHING` + re-read, or the unique index + one retry.
- `expectedVersion` checks stay as today.

## 3.6. Expiry
- `@Scheduled` daily job: `DELETE FROM app_versions WHERE created_at < now() - ttl` and the same for
  `doc_history`, in batches. Config keeps `apps.version-ttl` / `data.history-ttl`. Needs `@EnableScheduling`;
  safe with one API instance (a lock is only needed if scaled out).

## 3.7. Local dev and tests (U3)
- **zonky `embedded-postgres`** (test scope), started by a test-classpath configuration exposing a `DataSource`,
  used by `DevApplication` and `IntegrationTest` — same pattern as `EmbeddedMongoConfig`. Downloads a Postgres
  binary once (~tens of MB) into a cache dir.
- Tests clean tables between tests (`TRUNCATE`), keep the schema.
- Alternative kept in mind: Testcontainers (needs Docker — rejected by U3).

## 3.8. Railway
- Add Postgres from Railway's template; `api` gets `DATABASE_URL` (or `SPRING_DATASOURCE_*`) referencing it over
  the private network; enable its TCP proxy (U4).
- Order: add Postgres + variables **before** pushing the code (startup fails without a database); remove the Mongo
  service and its proxy **after** the new deploy works.
- The JDBC URL format differs from Railway's `postgresql://user:pass@host:port/db` — Phase 2 decides how the API
  reads it (separate `PGHOST`/`PGUSER`/… variables or a converted URL).

## 3.9. Docs
- `.claude/docs/mongodb.md` → `postgres.md` (schema, Railway, SQL client how-to, traps); `data-api.md` storage
  section; `AGENTS.md` stack/running/layout; `project-log.md` on close. The builder guide does not change (U2).

# 4. Acceptance criteria (draft)
1. No MongoDB dependency, class or variable remains; the API starts on Postgres only.
2. All existing behaviour tests (apps, data, filters, port fence, documents, auth) pass on embedded Postgres.
3. Filters behave as before for the whitelisted operators, including `$ne`/`$exists` on missing fields, nested
   paths and type-aware comparison; injection attempts are refused or bound harmlessly.
4. History: one row per replaced/deleted state, in the same transaction, also under concurrent writers.
5. Old versions and history are removed after their TTL by the scheduled job.
6. `npm run dev` works with nothing installed; on `test` the API runs on Railway Postgres, data inspectable from a
   SQL client; Mongo service removed.
7. Team Calendar and lunch-poll work unchanged after re-upload.

# 5. Risks
| Risk | Mitigation |
|---|---|
| Subtle filter semantics differ from Mongo (arrays, types, missing fields) | Port all `FilterTranslatorTest` cases to SQL-level tests run on real Postgres; document any intentional difference in the builder guide |
| SQL injection through field paths or values | Paths validated by the existing whitelist and bound as `text[]`; values always bound; no string concatenation of app input |
| Embedded Postgres on Windows (binary download, ports, orphaned processes) | zonky ships Windows binaries; shut down with the context; note in AGENTS.md like the orphaned `mongod` |
| Deploy order on Railway (code before database = failed start) | Railway steps before the push; Mongo removed only after verification |
| Larger change than it looks (24 files) | Contracts are covered by existing ITs; port them first, then swap the implementation |
