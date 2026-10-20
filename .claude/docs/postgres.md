# PostgreSQL — reference

How the platform stores everything, and why. The code and `db/migration/*.sql` are the source of truth; this file
keeps the decisions, the Railway setup and the traps. (Replaced MongoDB in SHC-0018; the SDK's Mongo-style filter
syntax was kept on purpose.)

## Model

| Table | Contents |
|---|---|
| `apps` | slug (PK), name, description, current `html`, `owner` (creator), `version`, `last_used_at`, `archived` + `archived_at`/`archived_by`, created/updated, `updated_by` |
| `app_versions` | every deploy: `app_slug` → `apps`, `version` (unique per app), html, `author`, `created_at` |
| `docs` | app data: `space` (= app slug, FK → `apps`), `collection`, `doc_id` (unique per space+collection), `data jsonb`, `version`, created/updated by/at |
| `doc_history` | earlier states: same identity + `version`, `op` (`update`/`delete`), previous `data`, previous `updated_by/at`, `changed_by/at` — no FK, outlives a deleted app |
| `flyway_schema_history` | Flyway's own bookkeeping |

- Schema only through **Flyway** migrations (`src/main/resources/db/migration/V<n>__<what>.sql`), applied at
  startup. Never edit an applied migration — add the next one.
- Access only through Spring **`JdbcClient`** with hand-written SQL (no JPA). Timestamps are `timestamptz`; the
  driver speaks `OffsetDateTime`, so `db/Timestamps` converts to/from `Instant`.
- Retention: `apps.version-ttl` / `data.history-ttl` (90 days), enforced by `db/ExpiryJob` at 03:30 every night
  (`db.expiry-cron`). Postgres has no TTL indexes. One API instance — a lock is needed if it is ever scaled out.

## Writes

- **Deploy**: `INSERT … ON CONFLICT (slug) DO UPDATE … version = apps.version + 1 RETURNING *` + version row, one
  transaction; concurrent deploys queue on the row lock.
- **Data `set` / `remove`**: `SELECT … FOR UPDATE` on the document, history row, then update/delete — one
  transaction. Concurrent writers to one document wait, never fail; each replaced state gets exactly one history
  row. An insert race is `ON CONFLICT DO NOTHING` + re-lock. No retry loop (MongoDB needed one).
- `db.add` ids: 24 hex = 8 hex epoch seconds + 16 random hex (same shape/order as Mongo ObjectIds; the builder
  guide promises it).

## Filters (Mongo syntax → SQL on JSONB, `data/FilterTranslator`)

- Field `a.b` → `data #> ?::text[]` with the path bound as `'{a,b}'`; every value bound as `?::jsonb` (or typed
  text/numeric/boolean for ranges). **No app input is ever in the SQL text.** Names are whitelisted first.
- Mongo semantics kept: equality on arrays = "contains"; `{a: null}` = null or missing; `$ne`/`$nin` match
  missing; ranges only within one JSON type (`CASE` guards the casts — SQL does not promise evaluation order);
  strings compare by code point (`COLLATE "C"`), never by locale; sort puts missing first, then numbers,
  strings, objects, arrays, booleans; `id`/`_updatedBy`/`_updatedAt` map to columns (`_updatedAt` compared as
  time from an ISO string).
- Known differences: an object inside an array matches by containment (partial), not exact element; `$in`/`$nin`
  on `_updatedAt` are refused.
- No GIN index: every query is bounded by `(space, collection)` via the unique index. Add one when an app's
  collection grows past ~100k documents.

## Configuration

- `spring.datasource.url=${DATABASE_JDBC_URL}`, `username=${DATABASE_USER}`, `password=${DATABASE_PASSWORD}` —
  no defaults on purpose (a deploy without them fails at startup).

## Railway (`test`)

- Service `Postgres` (Railway's database template, image on `:latest` = PostgreSQL 18). Major upgrades only through
  Railway's upgrade flow — changing the image tag does not run `pg_upgrade`.
- `api` variables are references, so a password rotation follows automatically:
  - `DATABASE_JDBC_URL` = `jdbc:postgresql://${{Postgres.PGHOST}}:${{Postgres.PGPORT}}/${{Postgres.PGDATABASE}}`
  - `DATABASE_USER` = `${{Postgres.PGUSER}}`, `DATABASE_PASSWORD` = `${{Postgres.PGPASSWORD}}`
- TCP proxy on (owner's choice: no important data on `test`) — connect with DBeaver / pgAdmin / IntelliJ using
  the public host:port and `PGUSER`/`PGPASSWORD` from the Postgres service's Variables. Useful queries:
  ```sql
  select slug, version, updated_by, last_used_at from apps order by last_used_at desc;
  select doc_id, data, version, updated_by from docs where space = 'team-calendar' and collection = 'marks';
  select doc_id, version, op, data, changed_by, changed_at from doc_history where space = 'lunch-poll' order by id;
  ```
- No backups yet (SHC-0017).
- The previous `Mongo Single Replica` service (and `MONGODB_URI` on `api`) is unused since SHC-0018 and waits for the
  owner's go to be deleted.

## Local development and tests

- `EmbeddedPostgresConfig` (test classpath) starts a real PostgreSQL (zonky `embedded-postgres`, binaries pinned
  to Railway's major in `pom.xml`) and provides the `DataSource`; Boot's own backs off, Flyway migrates it.
  Used by `DevApplication` (`npm run dev`) and every `*IT`. `embedded-postgres.properties` only satisfies the
  `${DATABASE_*}` placeholders.
- Tests reset with `TRUNCATE … RESTART IDENTITY CASCADE` (`IntegrationTest`); the schema stays.
- Flyway comes in through `spring-boot-starter-flyway` (Boot 4 no longer auto-configures it from `flyway-core`
  alone); `flyway-database-postgresql` is still needed next to it.
- A hard-killed dev run leaves `postgres.exe` processes behind (Task Manager); binaries unpack into
  `%TEMP%/embedded-pg`.
- **Never `JdbcClient…query(…).stream()`** without try-with-resources: the stream holds its connection until
  closed, and `toList()` does not close it. Use `.list()`. This leaked one connection per Data API query and
  exhausted Railway's pool of 10 within minutes (500s, "Connection is not available"). Tests now run through a
  4-connection HikariCP pool with a 2 s timeout (`EmbeddedPostgresConfig`), so a leak fails `DataServiceIT` fast.
- Forcing a failure inside a transaction in tests: a temporary `CHECK (false) NOT VALID` constraint on the table
  the write should fail on (see `DataServiceIT`, `AppRegistryIT`).
