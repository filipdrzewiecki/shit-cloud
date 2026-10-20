# Solution Design — SHC-0018: Switch from MongoDB to PostgreSQL (JSONB)

Input: `analysis.md` (decisions U1–U4).

---

# 1. Decisions carried from Phase 1

| # | Decision |
|---|---|
| U1 | Start fresh on `test` — no data migration |
| U2 | Keep the SDK / REST filter syntax; only the server translation changes |
| U3 | Embedded Postgres locally and in tests |
| U4 | Public TCP proxy on Railway's Postgres |
| A1 | *Assumption (user did not object):* equality on an array field also matches arrays **containing** the value, as in Mongo |

# 2. Design decisions

| # | Decision | Why |
|---|---|---|
| D1 | **Spring `JdbcClient`** with hand-written SQL everywhere; no JPA | One way to talk to the database (like `MongoTemplate` today); exact control of JSONB SQL |
| D2 | **Flyway** migrations in `resources/db/migration` (`V1__schema.sql`), run at startup on embedded and Railway alike | Schema in reviewable SQL, versioned; replaces `MongoIndexes` |
| D3 | **Tables** (no `_` prefix — app data is its own table now): `apps`, `app_versions`, `docs`, `doc_history`. Timestamps `timestamptz`, app data `jsonb` | See §4.2 |
| D4 | **Foreign keys**: `app_versions.app_slug` and `docs.space` → `apps.slug` (`on delete cascade`). `DataService` still checks the app exists first, for a clear `NO_SUCH_APP` on reads | The database itself guarantees no data without an app |
| D5 | **No GIN index** for now: every query is bounded by `(space, collection)` through the unique index `(space, collection, doc_id)`; filters run on that slice | Mongo had no data index either; add one when an app grows (trade-off §8) |
| D6 | **Filter → SQL** (`FilterTranslator` returns `SqlFilter(sql, params)`): the existing whitelist and name rules stay; output changes. Field `a.b` → `data #> ?::text[]` (path bound as `'{a,b}'`, segments already restricted to `[A-Za-z0-9_-]`); every value bound as `?::jsonb` (JSON text). No app input is ever concatenated into SQL | §5 has the operator table |
| D7 | **Type bracketing like Mongo**: `$gt/$gte/$lt/$lte` also require `jsonb_typeof` of field and value to match (JSONB alone would say `true > 5`). Missing fields never match a range | Same answers as before for mixed data |
| D8 | **Sort**: `data #> ?::text[]` with `NULLS FIRST` ascending / `NULLS LAST` descending (Mongo puts missing first); then `created_at`, `id` | Stable order as before |
| D9 | **Writes lock the row**: `set`/`remove` run `SELECT … FOR UPDATE` in a transaction, then history insert + update/delete. Writers on one document wait for each other instead of failing, so the **retry/back-off loop is removed**. Insert race: `INSERT … ON CONFLICT DO NOTHING`; if it inserted nothing, the transaction re-reads with `FOR UPDATE` and takes the update path | One history row per replaced state by construction; simpler than Mongo |
| D10 | **`add` ids**: 24 hex chars = 8 hex of epoch seconds + 16 random hex (same shape and time order as Mongo `ObjectId`) | The builder guide promises "a 24-char hex string" |
| D11 | **Deploy** = one `INSERT … ON CONFLICT (slug) DO UPDATE … version = apps.version + 1 … RETURNING *` + version insert, in a transaction. Concurrent deploys serialise on the row; a unique violation on `app_versions` still maps to 409 as a safety net | Atomic like the Mongo upsert, no write-conflict path |
| D12 | **Expiry**: `db/ExpiryJob`, `@Scheduled(cron = "${db.expiry-cron:0 30 3 * * *}")`, deletes `app_versions` older than `apps.version-ttl` and `doc_history` older than `data.history-ttl`; public `purge(Instant now)` for tests; logs counts | Replaces Mongo TTL indexes; one API instance, so no lock |
| D13 | **Connection**: `spring.datasource.url=${DATABASE_JDBC_URL}`, `username=${DATABASE_USER}`, `password=${DATABASE_PASSWORD}` — no defaults (fail fast, like `MONGODB_URI`). Railway values are references to the Postgres service | Railway's `postgresql://…` URL is not a JDBC URL; three plain variables need no conversion code |
| D14 | **Embedded Postgres** (U3): zonky `embedded-postgres` (test scope) with the binaries BOM pinned to **the same major as Railway's Postgres** (checked in Task 1). `EmbeddedPostgresConfig` on the test classpath provides the `DataSource` (Boot's own then backs off); used by `DevApplication` and `IntegrationTest`. Data gone on restart, as today | Zero install, no Docker, same dev experience |
| D15 | Tests reset with `TRUNCATE apps, app_versions, docs, doc_history RESTART IDENTITY CASCADE` | Schema stays, data doesn't |
| D16 | Removed: Mongo starter, flapdoodle, `mongo/` package, `embedded-mongo.properties`, `EmbeddedMongoConfig`, `MONGODB_URI`; the `_class` noise disappears by itself | Acceptance criterion 1 |
| D17 | **Builder guide**: syntax unchanged (U2); one sentence added on array equality (A1) and on mixed types not matching ranges | It is a contract with outside assistants |

# 3. Known gaps (accepted)
- Equality on an **object inside an array** uses JSONB containment (`[{a:1,b:2}]` matches `{a:1}`) — Mongo would
  need an exact element. Rare in apps; documented.
- Mixed-type values sort in Postgres's JSON order, which differs from Mongo's for mixed columns.
- No GIN index (D5).
- Expiry runs nightly, not continuously; rows live up to a day past their TTL.

# 4. Backend

## 4.1 Dependencies (`pom.xml`)
- Remove `spring-boot-starter-data-mongodb`, flapdoodle (+ property).
- Add `spring-boot-starter-jdbc`, `org.postgresql:postgresql` (runtime), `org.flywaydb:flyway-core` +
  `flyway-database-postgresql` (Boot-managed versions), test: `io.zonky.test:embedded-postgres` + binaries BOM.

## 4.2 Schema (`V1__schema.sql`)

```sql
create table apps (
    slug          text primary key,
    name          text        not null,
    description   text,
    html          text        not null,
    owner         text        not null,
    version       integer     not null,
    last_used_at  timestamptz not null,
    archived      boolean     not null default false,
    created_at    timestamptz not null,
    updated_at    timestamptz not null,
    updated_by    text        not null
);
create index apps_last_used_at on apps (last_used_at desc);

create table app_versions (
    id          bigint generated always as identity primary key,
    app_slug    text        not null references apps (slug) on delete cascade,
    version     integer     not null,
    name        text        not null,
    description text,
    html        text        not null,
    author      text        not null,
    created_at  timestamptz not null,
    unique (app_slug, version)
);
create index app_versions_created_at on app_versions (created_at);

create table docs (
    id          bigint generated always as identity primary key,
    space       text        not null references apps (slug) on delete cascade,
    collection  text        not null,
    doc_id      text        not null,
    data        jsonb       not null,
    version     bigint      not null,
    created_by  text        not null,
    created_at  timestamptz not null,
    updated_by  text        not null,
    updated_at  timestamptz not null,
    unique (space, collection, doc_id)
);

create table doc_history (
    id          bigint generated always as identity primary key,
    space       text        not null,
    collection  text        not null,
    doc_id      text        not null,
    version     bigint      not null,
    op          text        not null check (op in ('update', 'delete')),
    data        jsonb       not null,
    updated_by  text        not null,
    updated_at  timestamptz not null,
    changed_by  text        not null,
    changed_at  timestamptz not null
);
create index doc_history_doc on doc_history (space, collection, doc_id, version);
create index doc_history_changed_at on doc_history (changed_at);
```
`doc_history` has no foreign key: history must survive a later app delete until it expires.

## 4.3 Classes

| Class | Change |
|---|---|
| `service/AppRegistry` | `JdbcClient`; list/open/html/exists/deploy as D11; `TransactionTemplate` over the JDBC transaction manager |
| `service/SampleAppSeeder` | `select count(*) from apps`; inserts through `AppRegistry`-style SQL |
| `model/AppRecord`, `model/AppVersion` | Mongo annotations removed; row mappers |
| `data/FilterTranslator` | Same validation, output `SqlFilter` / `SqlSort` (D6–D8) |
| `data/DataService` | `JdbcClient`, D9, D10; `cleanData` and limits unchanged |
| `data/DocRecord`, `data/HistoryRecord` | Annotations removed; JSONB ↔ `Map` via Jackson |
| `db/ExpiryJob` (new), `@EnableScheduling` | D12 |
| `mongo/*` | deleted |

## 4.4 Properties
```properties
#====== DATABASE — PostgreSQL. No defaults: a deploy without them must fail at startup, not guess.
spring.datasource.url=${DATABASE_JDBC_URL}
spring.datasource.username=${DATABASE_USER}
spring.datasource.password=${DATABASE_PASSWORD}
```
`apps.version-ttl`, `data.history-ttl` keep their meaning (now enforced by `ExpiryJob`).

# 5. Filter → SQL

`P` = `data #> ?::text[]` (path parameter), `V` = `?::jsonb` (value parameter). Reserved fields map to columns:
`id` → `doc_id` (compared as `to_jsonb(doc_id)`), `_updatedAt` → `updated_at`, `_updatedBy` → `updated_by`.

| App filter | SQL |
|---|---|
| `{a: v}` / `$eq` | `(P = V OR (jsonb_typeof(P) = 'array' AND P @> jsonb_build_array(V)))` |
| `$ne` | `NOT coalesce(<eq>, false)` — also matches documents without the field |
| `$gt $gte $lt $lte` | `(P <op> V AND jsonb_typeof(P) = jsonb_typeof(V))` |
| `$in [..]` | `<eq>` against any element: `EXISTS (SELECT 1 FROM jsonb_array_elements(?::jsonb) e(v) WHERE P = e.v OR (jsonb_typeof(P) = 'array' AND P @> jsonb_build_array(e.v)))` |
| `$nin` | `NOT coalesce(<in>, false)` |
| `$exists: true/false` | `P IS NOT NULL` / `P IS NULL` (JSON `null` is a value; a missing field is SQL `NULL`) |
| `$and / $or` | `( … AND … )` / `( … OR … )` |
| several keys | joined with `AND` |

Always prefixed by `space = ? AND collection = ?`.

# 6. Frontend
No change (U2). Builder guide: D17.

# 7. Railway (user, in the dashboard — before the push)
1. Project `shitcloud` / `test` → **+ New → Database → PostgreSQL**. Note its major version (Task 1 pins the
   embedded binaries to it).
2. Postgres service → **Settings → Networking → TCP Proxy** on (U4) — gives a public host:port for a SQL client.
3. `api` → **Variables**:
   - `DATABASE_JDBC_URL` = `jdbc:postgresql://${{Postgres.PGHOST}}:${{Postgres.PGPORT}}/${{Postgres.PGDATABASE}}`
   - `DATABASE_USER` = `${{Postgres.PGUSER}}`
   - `DATABASE_PASSWORD` = `${{Postgres.PGPASSWORD}}`
4. After the new deploy is verified: remove `MONGODB_URI`, delete the `Mongo Single Replica` service and its volume
   (U1 — nothing to keep).

# 7a. Acceptance criteria
1. No MongoDB dependency, class or variable remains; the API starts on Postgres only.
2. All behaviour ITs (apps, data, filters, port fence, documents, auth) pass on embedded Postgres.
3. Filters match §5, including `$ne`/`$exists` on missing fields, nested paths, type bracketing, array membership;
   injection attempts in names, paths and values are refused or bound harmlessly.
4. One history row per replaced/deleted state, in the same transaction, also under concurrent writers.
5. `ExpiryJob` removes versions and history older than their TTL.
6. `npm run dev` works with nothing installed; on `test` the API runs on Railway Postgres, inspectable from a SQL
   client; Mongo removed.
7. Team Calendar and lunch-poll work unchanged after re-upload.

# 8. Implementation tasks (Phase 3)

- Task 1 — `pom.xml` (remove Mongo/flapdoodle, add JDBC/Postgres/Flyway/zonky, pin binaries to Railway's major), properties
- Task 2 — Flyway `V1__schema.sql`
- Task 3 — Test infra: `EmbeddedPostgresConfig`, `DevApplication`, `IntegrationTest` truncation; remove the Mongo test config
- Task 4 — `AppRegistry` + `AppRecord`/`AppVersion` + `SampleAppSeeder` on `JdbcClient`
- Task 5 — `FilterTranslator` → `SqlFilter`/`SqlSort` (§5)
- Task 6 — `DataService` on `JdbcClient` (row locks, history, insert race, ids); `DocRecord`/`HistoryRecord`
- Task 7 — `ExpiryJob` + scheduling; delete the `mongo/` package
- Task 8 — Builder guide note (D17); `AGENTS.md` (stack, running, layout); draft `postgres.md`
- Task 9 — Local end-to-end check (`npm run dev`, Data API with two guests, lunch-poll, Team Calendar)
- Task 10 — Railway (user, dashboard): Postgres, TCP proxy, `api` variables (§7 steps 1–3)
- Task 11 — Deploy (only on the user's order to commit/push) + acceptance; then §7 step 4 (user)

Phase 4 tests:
- `FilterTranslatorTest` (unit): validation cases kept (every refused construct); SQL shape and parameters per operator.
- `FilterSqlIT` (new): a fixture set of documents on real Postgres, one assertion per row of §5 — missing vs
  `null`, `$ne`/`$nin` on missing, type bracketing, arrays, nested paths, `$or` inside `$and`, sort with missing
  values, and injection attempts (`'); drop table docs; --` as value and as `$in` element) returning nothing harmful.
- `DataServiceIT`: kept; rollback test via a temporary `CHECK` constraint on `doc_history` instead of the Mongo
  validator; concurrency test kept (now without retries).
- `AppRegistryIT`, `SampleAppSeederIT`, `AppControllerIT`, `DataControllerIT`, `AppDocumentIT`, `PortFenceIT`: kept.
- `MongoIndexesIT` → `SchemaIT` (Flyway applied, tables/indexes/constraints exist).
- `ExpiryJobIT` (new): rows older than the TTL go, newer stay.

# 9. Trade-offs
| Choice | Cost accepted |
|---|---|
| Hand-written SQL (no JPA) | Row mapping code; in exchange JSONB queries are explicit and reviewable |
| Row locks instead of optimistic retries | Writers on one document queue briefly; fine at this scale |
| No GIN index | Filters scan one app's collection; revisit when one passes ~100k documents |
| Nightly expiry instead of TTL | Up to a day of extra retention; one more moving part (`ExpiryJob`) |
| Three DB variables instead of one URL | One more variable to set on Railway; no URL-parsing code |
