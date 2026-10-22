# App data (Data API, bridge, SDK) — reference

How apps store shared data, and why it is built this way. The code is the source of truth for details; this file
keeps the decisions, the rules apps live by and the traps already hit.

## Path of a call

```
app (apps origin, iframe)  ── postMessage {shitcloud:1, id, op, …} ──▶  host page (GUI origin)
  window.shitcloud.db.*                                                  shell/appBridge.ts + useAppBridge
                                                                         ├─ accept only: event.source === the app's
                                                                         │  iframe window AND event.origin === apps origin
                                                                         ├─ writes: space = slug from the ROUTE, never
                                                                         │  the message; only find may name another space
                                                                         └─ Data API with the user's token and
                                                                            X-ShitCloud-App: <route slug> ─▶ DataService
                                                                            (+ DependencyLog after success)
  ◀── postMessage {shitcloud:1, id, ok, result | error{code,message}} to the apps origin only
```

- Both host checks are needed: on a shared apps origin (`test`) every app has the same origin, only the window
  tells them apart.
- The SDK sends with target `'*'` (it can't know which GUI origin frames it; `frame-ancestors` guarantees a platform
  page) and accepts replies only when `event.source === window.parent`.
- Opened in a tab of its own, the app has no parent: every call rejects with `NOT_IN_SHITCLOUD` ("Open in new tab"
  is kept for debugging the HTML).

## SDK (`resources/apps/sdk.js`, `shitcloud.version = 3`, frozen)

```js
const me = await shitcloud.me();                                   // {id, displayName, guest}
const days = await shitcloud.db.find('days', {userId: me.id, date: {$gte: '2026-10-01'}}, {sort: {date: 1}, limit: 100});
await shitcloud.db.set('days', 'filip_2026-10-20', {type: 'off'});   // create or replace; {expectedVersion} optional
const id = await shitcloud.db.add('votes', {place: 'Pizza'});        // generated id (ObjectId hex)
await shitcloud.db.remove('days', 'filip_2026-10-20');              // false when nothing was there
const marks = await shitcloud.db.space('team-calendar').find('marks', {...});  // another app, read-only (v3)
```

- Results are **flat**: `{...fields, id, _version, _updatedBy, _updatedAt}`. On write, `id` and top-level `_` keys
  are dropped, so "read, change, write back" just works.
- Errors are `Error`s with `.code`: `BAD_REQUEST`, `NO_SUCH_APP`, `VERSION_CONFLICT`, `CONFLICT`, `TOO_LARGE`,
  `SIGNED_OUT`, `UNAVAILABLE`, `TIMEOUT` (15 s), `NOT_IN_SHITCLOUD`.
- No realtime: apps poll (the `lunch-poll` dev sample polls every 5 s).
- The full app rules for AI assistants live in `frontend/src/guide/builder-guide.md` (GUI page `/guide`, builders
  copy it into their own assistant); it is also the seed of the build-chat system prompt (SHC-0009). Keep it in
  step with the SDK, CSP, mirror and limits.

## Storage

PostgreSQL — tables, SQL and the filter translation are in `.claude/docs/postgres.md`.
- `docs`: `space`, `collection`, `doc_id`, `data jsonb` (the app's fields), `version`, created/updated by/at;
  unique `(space, collection, doc_id)` (also the index every query uses); `space` → `apps` foreign key.
- `doc_history`: the **previous** state (`version`, `data`, `updated_by/at`) + `op` (what replaced it:
  `update`/`delete`/`migrate`/`undo`/`restore`), `changed_by/at`; removed by `ExpiryJob` after `data.history-ttl`
  (90d); index `(space, collection, doc_id, version)`. Browsed and restored on the Data & history page (below).
- `set`/`remove` run in a transaction that first locks the document's row (`FOR UPDATE`), then writes history,
  then updates/deletes. Concurrent writers to one document queue up instead of failing, so there is no retry loop.
  `expectedVersion` mismatch → 409 `VERSION_CONFLICT`; `expectedVersion: 0` = must not exist yet.
- Last write wins by default (user decision); nothing is lost because every replaced state is in history.

## Rules and limits

- Space = an existing app's slug (else 404 `NO_SUCH_APP`). Write-only-own-space is enforced by the bridge; a user
  with curl has the same rights as through any app (accepted).
- **Cross-space reads** (SHC-0010): `db.space(slug)` has `find` only, so a write to another space cannot even be
  attempted; the bridge honours a message's `space` for `find` only. Any existing app is readable, archived
  included; deleted → `NO_SUCH_APP`. No new right: the API already let everyone read every space.
- **Dependency log** (`app_deps`, `data/DependencyLog`): the bridge names the app on screen in `X-ShitCloud-App`
  (allowed in CORS); after a successful call `DataController` records `(reader, space, collection, read|write)`.
  Insert on first use, `last_seen_at` refreshed at most hourly (`on conflict … where last_seen_at < now - 1h`), an
  unknown reader inserts nothing, a failed write is logged and ignored. Own-space use is recorded too. Rows go when
  either app is deleted (FK cascade) or after `data.deps-ttl` (90 d) unused (`ExpiryJob`). Self-declared reader →
  informational only. Read by `GET /api/deps/{space}` (builders; "Used by" on the app page) and the chat's `get_deps`.
- Everyone signed in reads and writes, **guests included**; writes record `guest:<name>` or the builder's email.
- Filters (`FilterTranslator`): rebuilt from a whitelist — implicit equality, `$eq $ne $gt $gte $lt $lte $in $nin
  $exists`, `$and`/`$or` at any level, dotted paths — translated to parameterised SQL on the JSONB `data` with
  MongoDB's meaning (array "contains", null = missing, type-bracketed ranges; details in `postgres.md`). `id`,
  `_updatedAt`, `_updatedBy` map to columns; any other `_`/`$` name → 400. No `$regex` (ReDoS), no `$` inside
  values. Depth ≤ 8, lists ≤ 500, sort ≤ 4 keys, default order `created_at` then `id`.
- Collection `[A-Za-z0-9_-]{1,64}`, id `[A-Za-z0-9_.:@-]{1,128}`, `data` an object ≤ 256 KB (JSON bytes),
  nesting ≤ 32, field names without `.` or leading `$`. `limit` 1–1000 (default 1000).
- The build chat reads data too, outside the Data API: `DataService.spaces()` (non-archived apps, collections, counts)
  and `sample(space, collection, n)` (newest first) behind `list_spaces` / `sample_docs` — builder-only, at most
  5 documents cut to ~2 KB each, sent to the model vendor (`chat-protocol.md`, "ShitCloud's tools").
- Error bodies `{code, message}` come from `DataExceptionHandler`, scoped to `DataController` only — Spring's default
  error body hides reasons, and enabling messages globally could leak 500 details.

## Data & history page (builders, SHC-0012)

- `/apps/:slug/data` (Manage → "🕘 Data & history"): collection tabs with current and removed counts, the records
  newest change first (offset pages of 50, id-prefix filter taken literally, "Removed records" switch), and a
  record's earlier states, newest first (≤ 200), each with its fields marked against the current state
  (`pages/fieldDiff.ts`) and "before a data change" for states at or before a `migrate` row. Shown raw (JSON): no
  schema exists. Owner: builders only, single records only, no hand editing, no app-wide change feed.
- API (`controller/HistoryController`, reads in `data/HistoryService`): `GET /api/apps/{slug}/data`,
  `…/data/{collection}?removed&prefix&offset&limit` → `{items, hasMore}`, `…/{collection}/{id}/history`,
  `POST …/{collection}/{id}/restore {historyId, expectedVersion}`.
- A removed record = an id in `doc_history` with no `docs` row; listed by its newest history row (`distinct on`),
  "removed by" = that row's `changed_by/at`.
- **Restore** (`DataService.restore`) is a write: shared space lock, the history row must be that document's (404
  `NO_SUCH_VERSION`), version guard (`expectedVersion` = what the builder saw, 0 = removed; else 409
  `VERSION_CONFLICT`, the GUI reloads the record); the replaced state goes to history as `restore`; a removed record
  comes back at history's highest version + 1, created by the builder. Never rewrites history.
- The page takes the app name from the list, not `GET /api/apps/{slug}` (that would mark the app used).

## Known gaps

- Path mode on `test`: a same-origin app in another tab could script another app's frame and use its bridge.
- Drafts (Build/Upload `srcdoc` previews) have no SDK and no bridge — owner's decision in SHC-0008 (the preview
  says data works after deploying); an in-memory sandbox store behind the bridge was the rejected alternative.
- Rate limits (parked). Version numbers can repeat for one id: a removed document created again through the Data API
  starts at 1 (restore and undo continue after history's highest) — history rows are addressed by row id.
- Converting an app's data to a new shape is the build chat's `propose_migration`, applied with Replace HTML:
  `.claude/docs/data-migrations.md` (also why every write takes a shared space lock). Dates travel as ISO strings.

## Testing

- `FilterTranslatorTest` (validation, no input in SQL text), `FilterSqlIT` (every operator's meaning on real
  Postgres), `DataServiceIT` (history, rollback via a temporary `CHECK (false)` constraint, version guard, 3
  concurrent writers → one history row per replaced state), `DataControllerIT`, `DataServiceTest` (ids).
- Frontend: `npm test --prefix frontend` (Vitest, node env). `appBridge.test.ts` (accept rules, space from the route,
  error mapping) and `sdk.test.ts` (runs `sdk.js?raw` against hand-made windows). **jsdom is not used**: its
  `postMessage` never sets `event.source`, so it cannot exercise the SDK's source check.
