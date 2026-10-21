# Analysis — SHC-0010: Dependency log + cross-space reads

## Metadata

| Field               | Value                                                                                              |
|---------------------|----------------------------------------------------------------------------------------------------|
| **Description**     | Let an app read (never write) another app's data through the SDK, record which app reads/writes which space, and use that record to warn — in the build chat and before destructive actions — when a change may break other apps. |
| **Ticket**          | SHC-0010                                                                                           |
| **Endpoints**       | `POST /api/data/{space}/{collection}/query` (now also from other apps), new `GET /api/deps/{space}` |
| **Key classes**     | `resources/apps/sdk.js`, `frontend/src/shell/appBridge.ts`, `data/DataService`, `controller/DataController` |
| **Related classes** | `buildchat/*` (new `get_deps` tool, prompt), `db/ExpiryJob`, `AppManageMenu` delete dialog, builder guide |
| **References**      | `shitcloud-handoff.md` §5, §6.1, §6.3, §7.1, §10; `.claude/docs/data-api.md`; `.claude/docs/app-management.md` |

---

# 1. Problem

## 1.1. What is the problem?
- Apps are islands: an app reads and writes only its own space (the bridge takes the space from the route). A
  report over team-calendar's data, a dashboard combining two apps, a "who is in the office" widget — none can be
  built. The acceptance set of SHC-0009 had to leave this out.
- Nobody knows who depends on what. Changing team-calendar's document shape, or deleting it with its data, can
  silently break any app that reads it. The handoff accepts that risk **only** with warnings from a dependency log
  (§10: "Changing one app breaks another — Accepted — Warnings from `_deps` in chat").

## 1.2. Why is it a problem? What may be problematic to implement?
- **Who is the reader?** The Data API today does not know which app calls it: the host page calls
  `/api/data/{space}/…` with the user's token. To log "app A reads space B", the host must tell the server which app
  is on screen. That claim comes from our GUI (trusted code), but anyone with a token and curl can send anything —
  the log is informational, never a security boundary.
- **Read-only must hold.** The other space must be readable and never writable from the app. Today the bridge's
  "space from the route" rule is the only thing that keeps writes in the own space (the API itself lets every
  signed-in user write anywhere — accepted in SHC-0004). Cross-space reads must not weaken that: writes keep using
  the route's space, only `find` gets a second target.
- **Cost of logging.** "Upserted on every data call" (handoff §5) = one extra write per read; apps poll every few
  seconds. Needs throttling.
- **SDK is frozen at version 2** and injected into every app: the addition must be backwards compatible.
- **Draft previews have no SDK/bridge** (SHC-0008 decision) — a cross-space app still can't show data before deploy.

## 1.3. How the owner would like to solve it (roadmap + handoff)
- `shitcloud.db.space('presence-calendar')` — read-only access to another app's space (handoff §6.3).
- Dependency log: app, space, mode (read/write), last seen — upserted on data calls (§5); `GET /api/deps/{space}`
  (§6.1); `get_deps(space)` tool for the chat to warn before changing a data shape (§7.1). It informs, it does not
  block.

# 2. Context

## 2.1. Key context
- Data path: app → `postMessage` → `shell/appBridge.ts` (`acceptRequest` checks frame + origin; `space` = route slug)
  → `api/data.ts` → `DataController` → `DataService` (`docs` table, `space` FK to `apps`).
- Everyone signed in (guests included) may read every space through the API already; cross-space reads add no new
  read right, only a supported way for apps to use it.
- Archived apps keep their data; deleted apps lose it (`NO_SUCH_APP` everywhere).
- `ExpiryJob` removes old history/versions nightly; a deps table would need its own retention.
- The chat has `list_spaces` / `sample_docs` (SHC-0009); its prompt currently says "an app cannot read another app's
  data yet".

## 2.2. Main flows

```
report app (space: office-report)                         host page                         API
  shitcloud.db.space('team-calendar').find('marks', f) ─▶ op:find, space:'team-calendar' ─▶ POST /api/data/team-calendar/marks/query
                                                          (writes: always the route's      header: reader = office-report
                                                           space; space() has no writers)  ─▶ query + deps upsert
                                                                                              (office-report, team-calendar, marks, read)
build chat, changing team-calendar
  get_deps('team-calendar') ─▶ [{app: office-report, collection: marks, mode: read, lastSeen: 2 h ago}]
  → "office-report reads `marks`; renaming `type` will break it" (informs, does not block)
```

## 2.3. Current state of things
- Missing: SDK `space()`, bridge op for a foreign space, a way to name the reader, the deps table + upsert,
  `GET /api/deps/{space}`, `get_deps` tool, prompt/guide text, retention.
- Out of scope: migrations and read-only-after-migration spaces (SHC-0011), history UI (SHC-0012).

# 3. Open questions for Phase 2

| # | Question | Proposal |
|---|---|---|
| Q1 | SDK shape | `shitcloud.db.space(slug)` returns a frozen object with **`find` only** (same signature as `db.find`). No `set/add/remove` on it, so a write cannot even be attempted. Own space unchanged. SDK version 2 → 3. `space(ownSlug)` works too (same as `db`). |
| Q2 | How the server learns the reading app | The bridge sends the app on screen as a header (`X-ShitCloud-App: <route slug>`) on every Data API call. No header (curl, exports, other tools) → no log entry. Self-declared by our GUI; informational only. |
| Q3 | Log granularity | `(reader_app, space, collection, mode)` with `first_seen_at`, `last_seen_at`. Collection added to the handoff's shape: warnings can then say *which* collection is read ("reads `marks`"). Own-space use logged too (mode `write` for set/add/remove, `read` for find), so the log also tells whether an app is in use. |
| Q4 | Logging cost | Insert on first use; update `last_seen_at` at most once an hour per row (`on conflict … where last_seen_at < now() - 1h`) — one cheap no-op statement per call otherwise. Outside the data transaction; a failed log write never fails the read. |
| Q5 | Retention | Rows not seen for 90 days removed by `ExpiryJob` (same as history); rows of a deleted app (as reader or as space) removed with it. |
| Q6 | Where the log shows | `GET /api/deps/{space}` (builders; everyone?), the chat's `get_deps` tool, and the **Delete with data** dialog ("2 other apps read this app's data: …"). A "Used by" line on the app page is optional — owner's call. |
| Q7 | Which spaces may be read | Any existing app, archived ones included (data is kept). Deleted → `NO_SUCH_APP`. |
| Q8 | Chat behaviour | Prompt: before changing a data shape or deleting fields, call `get_deps` and name the apps that will break; apps may now read other spaces via `space()`. The guide documents `space()` (read-only, `find` only). |

# 4. Owner's decisions (2026-10-21)

- **U1 — SDK:** `shitcloud.db.space(slug)` → frozen object with **`find` only**; writes to another space cannot be
  attempted. Own `db` unchanged; SDK version 3.
- **U2 — Reader:** the host page sends `X-ShitCloud-App: <slug on screen>` on every Data API call; no header → not
  logged. Informational, not a security boundary.
- **U3 — Log row:** `(reader app, space, collection, read|write)` + first/last seen; own-space use logged too
  (precise warnings per collection, and "is this app still used").
- **U4 — Cost:** insert on first use, `last_seen` refreshed at most hourly; outside the data transaction, a failed
  log write never fails the call.
- **U5 — Retention:** rows unused for 90 days removed nightly; removed at once when either app is deleted.
- **U6 — Shown in:** the chat (`get_deps` warnings) and a **"Used by"** line on the app page (builders), served by
  `GET /api/deps/{space}`. **Not** in the Delete-with-data dialog.
- **U7 — Readable:** any existing app, archived included; deleted → `NO_SUCH_APP`.
- **U8 — Chat:** calls `get_deps` before changing a data shape and names the apps that will break (informs, does not
  block); the guide and prompt document `space()`, so the chat builds apps that read other apps' data.
