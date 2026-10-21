# Solution design — SHC-0010: Dependency log + cross-space reads

Input: `analysis.md` (U1–U8), `.claude/docs/data-api.md`, `.claude/docs/chat-protocol.md`.

# 1. Approach

## 1.1. Storage — `V4__deps.sql`

```sql
-- Which app used which data, from the host page's X-ShitCloud-App header. Informational: it feeds warnings, it
-- guards nothing. Both foreign keys cascade, so deleting either app removes its rows (U5).
create table app_deps (
    reader        text        not null references apps (slug) on delete cascade,
    space         text        not null references apps (slug) on delete cascade,
    collection    text        not null,
    mode          text        not null check (mode in ('read', 'write')),
    first_seen_at timestamptz not null,
    last_seen_at  timestamptz not null,
    primary key (space, collection, mode, reader)
);
create index app_deps_last_seen_at on app_deps (last_seen_at);
create index app_deps_reader on app_deps (reader);   -- the cascade from apps(slug) as reader
```

Primary key starts with `space`: every lookup is "who uses this space".

## 1.2. Logging — `data/DependencyLog`

```java
void record(String reader, String space, String collection, Mode mode)   // READ | WRITE
List<DependencyView> users(String space)   // rows of the space, newest first, with the reader's name + archived
```

Upsert, at most one real write per row per hour (U4); an unknown reader slug inserts nothing (the `select … where
exists` form keeps the FK from throwing):

```sql
insert into app_deps (reader, space, collection, mode, first_seen_at, last_seen_at)
select ?, ?, ?, ?, ?, ? where exists (select 1 from apps where slug = ?)
on conflict (space, collection, mode, reader) do update set last_seen_at = excluded.last_seen_at
where app_deps.last_seen_at < excluded.last_seen_at - interval '1 hour'
```

Called by `DataController` **after** the operation succeeded, outside its transaction; any `DataAccessException` is
logged at WARN and swallowed (U4). No header, a blank one, or one that is not slug-shaped → nothing recorded.
`find` → `read`; `set`/`add`/`remove` → `write`.

Retention: `ExpiryJob.purge` also deletes rows with `last_seen_at` older than `data.deps-ttl=90d` (U5).

## 1.3. API

| Change | Detail |
|---|---|
| `DataController` | `@RequestHeader(value = "X-ShitCloud-App", required = false) String reader` on all four endpoints → `DependencyLog.record`. Everything else unchanged (any existing space readable, archived included — U7; `requireCollection` already 404s deleted apps). |
| `GET /api/deps/{space}` | new `DepsController`, builders only: `[{app, appName, archived, collection, mode, firstSeenAt, lastSeenAt}]`; unknown space → 404 `NO_SUCH_APP`. |
| CORS | `X-ShitCloud-App` added to the allowed headers (`SecurityConfig`), or the browser's preflight drops every data call. |

## 1.4. SDK v3 and the bridge (U1)

`sdk.js`:
```js
space: function (slug) {
  return Object.freeze({
    // find('marks', filter, options) on another app's data; read-only: there is nothing else to call.
    find: function (collection, filter, options) { … request('find', {space: slug, collection, …}) … }
  });
}
```
`db.space` sits next to `find/set/add/remove`; `shitcloud.version = 3`. A non-text slug rejects with `BAD_REQUEST`.

`appBridge.ts`: only `find` reads an optional `space` argument (non-empty text, else `BAD_REQUEST`); `set`, `add`,
`remove` keep using the route's space and ignore any `space` in the message — the read-only rule lives in the
bridge, as the own-space rule does today. `useAppBridge` gives the backend the reader: `dataApi.*` take an optional
`reader` and send it as `X-ShitCloud-App` (only the bridge calls `dataApi`).

## 1.5. "Used by" on the app page (U6)

`AppHostPage`, builders only: one line under the title when **other** apps used this app's data in the last 90 days
— "Data used by office-report, lunch-stats" (each a link to the app; archived ones marked). Own rows are filtered
out on the client. Nothing shown when there are none. TanStack query `['deps', slug]`.

## 1.6. Chat (U8)

- `buildchat/GetDepsTool` — `get_deps(space)`: model reads the rows grouped by collection, **other apps only** plus a
  line whether the app itself was seen ("team-calendar itself: reads people, marks; writes marks"); the user sees
  "Checked who uses team-calendar's data (2 other apps)". Unknown space → `ToolException`.
- `chat-rules.md` (Data section): before changing or removing a field, a collection or its shape, call `get_deps`
  for the app's space and name in the reply which apps read what will change; it does not block. Apps may read
  other apps' data with `shitcloud.db.space(slug).find(...)` (read-only); learn the shape with `list_spaces` +
  `sample_docs` first. Remove "cannot read another app's data yet".
- `BuildChatPrompt` rework section: "call `get_deps` before changing how data is stored".
- `builder-guide.md`: new subsection "Reading another app's data" (`space()`, `find` only, slugs, `NO_SUCH_APP`,
  the other app may change its shape — read defensively); SDK version 3 in the reference.
- `toolLabels.ts`: line for `get_deps`.

# 2. Trade-offs

- **Self-declared reader**: anyone with a token can claim any reader or skip the header; acceptable because the
  log only informs and every signed-in user can already read every space (SHC-0004 decision, unchanged).
- **Hourly refresh**: "last seen" is up to an hour stale — irrelevant for warnings measured in days.
- **Own-space rows** make the table ~(apps × collections × 2) rows plus cross reads — small at this scale.
- **Read-only lives in the bridge**, like the own-space rule; the API still accepts writes to any space from curl
  (accepted risk, `updatedBy` + history).
- **Drafts** still have no bridge: an app reading another space shows data only after deploy (SHC-0008 decision).
- **Not in the Delete dialog** (owner, U6): deleting an app with data that others read gives no warning beyond the
  "Used by" line on its page.

# 3. Decisions recorded
U1–U8 as in `analysis.md` §4.

# 4. Phase 3 tasks

- Task 1 — `V4__deps.sql` + `data/DependencyLog` (`record`, `users`) + `data.deps-ttl`
- Task 2 — `DataController`: `X-ShitCloud-App` → `record` after success; CORS allows the header
- Task 3 — `ExpiryJob`: remove rows unused for `data.deps-ttl`
- Task 4 — `DepsController` `GET /api/deps/{space}` (builders)
- Task 5 — SDK v3 `db.space(slug).find`; bridge `find` with optional `space`; `dataApi` sends the reader
- Task 6 — "Used by" line on the app page (builders)
- Task 7 — Chat: `GetDepsTool` + register + tool line; prompt (`chat-rules.md`, rework section); guide section
- Task 8 — Docs: `data-api.md`, `chat-protocol.md`, `app-management.md` ("Used by"), `postgres.md` (table), `AGENTS.md`
- Task 9 — Deploy (owner's order) + check on `test`
