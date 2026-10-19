# App data (Data API, bridge, SDK) — reference

How apps store shared data, and why it is built this way. The code is the source of truth for details; this file
keeps the decisions, the rules apps live by and the traps already hit.

## Path of a call

```
app (apps origin, iframe)  ── postMessage {shitcloud:1, id, op, …} ──▶  host page (GUI origin)
  window.shitcloud.db.*                                                  shell/appBridge.ts + useAppBridge
                                                                         ├─ accept only: event.source === the app's
                                                                         │  iframe window AND event.origin === apps origin
                                                                         ├─ space = slug from the ROUTE, never the message
                                                                         └─ Data API with the user's token ─▶ DataService
  ◀── postMessage {shitcloud:1, id, ok, result | error{code,message}} to the apps origin only
```

- Both host checks are needed: on a shared apps origin (`test`) every app has the same origin, only the window
  tells them apart.
- The SDK sends with target `'*'` (it can't know which GUI origin frames it; `frame-ancestors` guarantees a platform
  page) and accepts replies only when `event.source === window.parent`.
- Opened in a tab of its own, the app has no parent: every call rejects with `NOT_IN_SHITCLOUD` ("Open in new tab"
  is kept for debugging the HTML).

## SDK (`resources/apps/sdk.js`, `shitcloud.version = 2`, frozen)

```js
const me = await shitcloud.me();                                   // {id, displayName, guest}
const days = await shitcloud.db.find('days', {userId: me.id, date: {$gte: '2026-10-01'}}, {sort: {date: 1}, limit: 100});
await shitcloud.db.set('days', 'filip_2026-10-20', {type: 'off'});   // create or replace; {expectedVersion} optional
const id = await shitcloud.db.add('votes', {place: 'Pizza'});        // generated id (ObjectId hex)
await shitcloud.db.remove('days', 'filip_2026-10-20');              // false when nothing was there
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

- `docs`: `{space, collection, docId, data: {…app fields…}, version, createdBy, createdAt, updatedBy, updatedAt}`,
  unique `{space, collection, docId}` (also the index every query uses).
- `_history`: the **previous** state (`version`, `data`, `updatedBy`, `updatedAt`) + `op` (`update`/`delete`),
  `changedBy`, `changedAt`; TTL `data.history-ttl` (90d, changed via `collMod`), index `{space, collection, docId,
  version}` for restore (SHC-0012).
- `set`/`remove` run in a transaction: read → history insert → write **conditional on the version read**. A lost race
  (write conflict 112, transient label, duplicate key, 0 matched) is retried up to 5× with a random 5–20 ms × attempt
  pause — Mongo fails colliding transactions immediately, so in-step retries collide again. With `expectedVersion`
  a collision is final (409 `VERSION_CONFLICT`). `expectedVersion: 0` = must not exist yet.
- Last write wins by default (user decision); nothing is lost because every replaced state is in history.

## Rules and limits

- Space = an existing app's slug (else 404 `NO_SUCH_APP`). Write-only-own-space is enforced by the bridge; a user
  with curl has the same rights as through any app (accepted).
- Everyone signed in reads and writes, **guests included**; writes record `guest:<name>` or the builder's email.
- Filters (`FilterTranslator`): rebuilt from a whitelist — implicit equality, `$eq $ne $gt $gte $lt $lte $in $nin
  $exists`, `$and`/`$or` at any level, dotted paths. App fields are moved under `data.`; `id`, `_updatedAt`,
  `_updatedBy` map to platform fields; any other `_`/`$` name → 400. No `$regex` (ReDoS), no `$` inside values.
  Depth ≤ 8, lists ≤ 500, sort ≤ 4 keys, default order `createdAt` then `_id`.
- Collection `[A-Za-z0-9_-]{1,64}`, id `[A-Za-z0-9_.:@-]{1,128}`, `data` an object ≤ 256 KB (JSON bytes),
  nesting ≤ 32, field names without `.` or leading `$`. `limit` 1–1000 (default 1000).
- Error bodies `{code, message}` come from `DataExceptionHandler`, scoped to `DataController` only — Spring's default
  error body hides reasons, and enabling messages globally could leak 500 details.

## Known gaps

- Path mode on `test`: a same-origin app in another tab could script another app's frame and use its bridge.
- Drafts (Build/Upload `srcdoc` previews) have no SDK and no bridge — with the chat package (SHC-0008).
- No cross-space reads (SHC-0010), history UI/restore (SHC-0012), rate limits (SHC-0015). Dates travel as ISO strings.

## Testing

- `FilterTranslatorTest`; `DataServiceIT` (history, rollback via a `collMod` validator that rejects every history
  insert, version guard, 3 concurrent writers → one history entry per replaced state); `DataControllerIT`.
- Frontend: `npm test --prefix frontend` (Vitest, node env). `appBridge.test.ts` (accept rules, space from the route,
  error mapping) and `sdk.test.ts` (runs `sdk.js?raw` against hand-made windows). **jsdom is not used**: its
  `postMessage` never sets `event.source`, so it cannot exercise the SDK's source check.
