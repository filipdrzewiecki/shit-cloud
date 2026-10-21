# Implementation — SHC-0010: Dependency log + cross-space reads

Owner: "go, no gates" (2026-10-21) — Tasks 1–8 without per-task gates; Task 9 needs the owner's deploy order.

## Task 1 — Storage + log
- `V4__deps.sql`: `app_deps` (PK `space, collection, mode, reader`; both slugs FK → `apps` on delete cascade;
  indexes on `last_seen_at`, `reader`).
- `data/DependencyLog`: `record(reader, space, collection, Mode)` — slug-shaped reader only; `insert … select … where
  exists (app)` + `on conflict … do update … where last_seen_at < now - 1h`; `DataAccessException` → WARN, swallowed.
  `users(space)` joins the reader's name/archived, newest first; unknown space → `DataException NO_SUCH_APP`.
  `data/DependencyView`. `DataProperties.depsTtl` + `data.deps-ttl=90d`.

## Task 2 — Recording + CORS
- `DataController`: optional `X-ShitCloud-App` header (`READER`) on all four endpoints; recorded after the call
  succeeded (`find` → read, others → write). `SecurityConfig` CORS allows the header.

## Task 3 — Retention
- `ExpiryJob.purge` deletes `app_deps` rows unused for `data.deps-ttl`; log line counts them.

## Task 4 — Endpoint
- `controller/DepsController` `GET /api/deps/{space}` (builders). `DataExceptionHandler` now also covers it
  (404 `{code: NO_SUCH_APP}`).

## Task 5 — SDK v3, bridge, header
- `sdk.js`: shared `find(space, …)`; `db.space(slug)` → frozen `{find}`; non-text slug rejects `BAD_REQUEST`;
  `version: 3`.
- `appBridge.ts`: `find` takes an optional `space` (`optionalText`); writes keep the route's space.
- `api/client.ts`: `RequestOptions.headers`; `put`/`delete` accept options. `api/data.ts`: every call takes the
  `reader` and sends `X-ShitCloud-App`. `useAppBridge` binds the reader to the route slug.

## Task 6 — "Used by"
- `api/types.ts` `DependencyView`, `api/queries.ts` `useDeps`, `pages/usedBy.ts#otherUsers` (own use out, one entry
  per app with its collections), `AppHostPage` `UsedBy` (builders; links, "(archived)", collections in the title),
  `.used-by` style.

## Task 7 — Chat
- `buildchat/GetDepsTool` (`get_deps(space)`): model reads one line per app ("office-report (Office report): reads
  marks; last used 2026-10-21", own app marked "the app itself"); user sees `{space, otherApps}`. Registered.
- `toolLabels.ts`: "Checked who uses team-calendar's data (2 other apps)".
- `chat-rules.md`: `get_deps` before changing a data shape, name the apps that break, go ahead; `space().find` for
  reading other apps, learn the source with `list_spaces` + `sample_docs`. Rework paragraph: `get_deps` with the
  slug. Guide: "Reading another app's data" + `NO_SUCH_APP` in the error table.

## Task 8 — Docs
- `data-api.md` (path, SDK v3, cross-space rule, dependency log), `postgres.md` (`app_deps`, retention),
  `app-management.md` ("Used by", not in Delete), `chat-protocol.md` (`get_deps`), `AGENTS.md` (layout, V4).

## Checks
- `mvn -q compile test-compile` and `npm run typecheck` clean. Tests not run. Known to need updating in Phase 4:
  `appBridge.test.ts` "uses the space of the app on screen, never one from the message" (find now honours `space`),
  `sdk.test.ts` version 2 → 3.

# Phase 4 — Tests (written, not run — the owner runs them)
- added (backend): `data/DependencyLogIT` (row per reader/space/collection/mode, hourly refresh, unknown or
  malformed reader and unknown space swallowed, cascade on delete, `users` with names/archived/order, 404),
  `controller/DepsControllerIT` (builders only, shape, 404 code), `buildchat/GetDepsToolTest` (`describe`)
- modified (backend): `DataControllerIT#appOnScreen_isRecordedAsTheReader_afterSuccessfulCallsOnly`,
  `ExpiryJobIT#purge_forgetsDependenciesUnusedForTheirRetention`,
  `SecurityIT#preflight_allowsTheHeaderThatNamesTheAppOnScreen`
- added (frontend): `pages/usedBy.test.ts`
- modified (frontend): `shell/appBridge.test.ts` (find default space, find in another space, writes ignore a
  message's space, bad space), `shell/sdk.test.ts` (version 3, `space().find`, only `find`, own find names no
  space, bad slug), `chat/toolLabels.test.ts` (`get_deps`)
