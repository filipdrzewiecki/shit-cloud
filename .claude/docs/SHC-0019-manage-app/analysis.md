# Analysis — SHC-0019: Manage a deployed app — remove, download, replace HTML

## Metadata

| Field               | Value |
|---------------------|-------|
| **Description**     | Builders can archive an app (hide it, keep its data, restore later), delete it with all its data, replace its HTML in place, and anyone signed in can download its HTML or its data. |
| **Ticket**          | SHC-0019 (owner's request 2026-10-20; ordered before SHC-0005) |
| **Endpoints (draft)** | archive / restore, delete with data, replace HTML, data export — under `/api/apps/{slug}` |
| **Key classes**     | `AppController`, `AppRegistry`, `DataService` (export), frontend `AppHostPage`, `AppListPage`, `api/queries.ts` |
| **Related classes** | `apps` / `app_versions` / `docs` / `doc_history` tables, `AppDocumentController` (serves archived apps), `ControllerAuthorizationIT` |
| **References**      | `.claude/docs/postgres.md`, `.claude/docs/data-api.md`, `.claude/docs/app-origin.md`, `.claude/project-log.md` |

---

# 1. Problem

## 1.1. What is the problem?
- A deployed app can only be changed by deploying under **exactly the same name**; there is no way to remove it,
  hide it, or get its HTML or data back out.
- Apps are disposable by design ("nobody should feel bad deleting one"), but the platform offers no delete; data
  is durable, but there is no way to take a copy.
- `test` already collects throwaway apps (samples, tests) that nobody can clean up without SQL.

## 1.2. Why is it a problem? What may be problematic to implement?
- **Two very different removals.** "HTML only" must be fully reversible (data untouched, app comes back);
  "with data" is irreversible and must be hard to trigger by accident.
- **Archived state already exists but means something else.** `apps.archived` was meant for automatic 30-day
  archiving (SHC-0014): opening an archived app silently un-archives it (`AppRegistry.open`). An explicit archive
  must not be undone by a click (U4), so `open` changes.
- **What else reads an app's slug**: the app document on the apps origin, the Data API (`space` = slug), the host
  page. Archived apps must keep working there (U4: opens with a Restore bar); deleted apps must 404 everywhere.
- **Delete with data** spans four tables. `docs` and `app_versions` cascade from `apps`; `doc_history` has no
  foreign key (designed to outlive a deleted app) — U5 wants it deleted too, explicitly, in the same transaction.
- **Slug reuse.** After a delete, deploying the same name must start a brand-new app with no leftovers.
- **Replace HTML in place** = a new version of the same slug, data untouched — today's same-name deploy does
  exactly that; the gap is UI (pick the app, not retype its name) and keeping name/description.
- **Data export size.** One app's documents can be many; the export must not load unbounded data into memory
  carelessly, and must stay a plain, readable JSON file.

## 1.3. How user would like to solve it
1. **Remove**: *HTML only* → archived (hidden from the list, data kept, can be brought back); *with data* →
   app + versions + documents (+ history, U5) gone.
2. **Download**: two buttons — **`<app title>.html`** and **`data.json`** (U2).
3. **Replace the HTML** of an existing app without touching its data.

### User decisions (Phase 1)
| # | Decision |
|---|---|
| U1 | **Any builder** may archive, restore, delete and replace any app (as deploying already works) |
| U2 | Download = **two buttons**: the HTML as `<title>.html`, the data as `data.json` |
| U3 | **No import** of downloaded data in this ticket (export only) |
| U4 | Opening an **archived** app shows it (it still runs, data is there) with a bar *"This app is archived — Restore"*; only Restore brings it back to the list |
| U5 | Delete with data also deletes the documents' **history** — everything goes at once |

# 2. Context

## 2.1. Key context
- Tables (`postgres.md`): `apps` (slug PK, `archived`), `app_versions` (FK → apps, cascade), `docs` (FK `space`
  → apps, cascade), `doc_history` (no FK). `ExpiryJob` removes old versions/history.
- `GET /api/apps/{slug}` returns `{app, html, frameUrl}` and marks the app used; `POST /api/apps` deploys by name
  (slug from name). Deploy and chat are builder-only; listing/opening is for everyone signed in.
- App list already has "Show archived (n)" and an `archived` badge; nothing sets `archived = true` today except
  dev samples.
- Every controller method needs `@PreAuthorize` (`ControllerAuthorizationIT`).
- The apps origin serves `/{slug}/` publicly by slug (`AppDocumentController`); a deleted slug must 404 there too
  (it reads `apps.html`, so it will).

## 2.2. Main flows

```
Host page (builder)  ──▶  ⋯ menu:  Replace HTML…  | Download HTML | Download data | Archive | Delete with data…
                             │            │               │               │             │
                             ▼            ▼               ▼               ▼             ▼
                  pick file, preview,  client-side   GET export      archived=true   confirm by typing the
                  confirm → new version blob <title>  → data.json     (Restore bar    app name → app, versions,
                  (same slug, data kept) .html                         + list filter)  docs, history deleted
Guest / user: Download HTML, Download data (they can already read everything through the app).
```

## 2.3. Current state of things
- No archive / restore / delete / replace / export endpoints; `open()` un-archives; the host page has only "Open
  in new tab".

# 3. Options and open points for Phase 2

## 3.1. Archive vs. the planned automatic archiving (SHC-0014)
- Keep one `archived` flag for both, change `open()` to stop un-archiving (U4) → SHC-0014's auto-archive would then
  also need Restore. Or add `archived_by` / `archived_at` (+ reason) so the UI can say "archived by Anna on …"
  and SHC-0014 can decide later. Leaning: **`archived_at` + `archived_by` columns** (Flyway V2), `archived` stays
  the flag; `open()` no longer touches it.

## 3.2. Replace HTML
- Endpoint taking only `{html}` (name/description unchanged) → new version via the existing upsert path; or reuse
  `POST /api/apps` with the slug. Leaning: **`PUT /api/apps/{slug}/html`**, so a replace can never accidentally
  create a new app from a typo.
- UI: file picker → `DraftFrame` preview → "Replace" (with the size check and 413 message as deploy).
- Replacing an archived app: allowed, stays archived? or restores? Leaning: stays as it is (archive is separate).

## 3.3. Delete with data
- One transaction: `delete from doc_history where space = ?`, `delete from apps where slug = ?` (cascade does
  versions and docs). 404 if the app does not exist.
- Confirmation in the GUI: type the app's name (GitHub-style), show counts ("12 documents in 3 collections will be
  deleted") — counts from the export endpoint or a small stats endpoint.
- Optional server-side guard: require `?confirm=<slug>` so a stray DELETE cannot wipe data.

## 3.4. Download
- **HTML**: client-side from `GET /api/apps/{slug}` (`html` is already there) → `Blob` → `<sanitised title>.html`.
  Downloading should not count as "using" — acceptable, or use a side-effect-free endpoint. Leaning: reuse, it is
  only a timestamp.
- **Data**: `GET /api/apps/{slug}/export` → `data.json`:
  `{ app: {slug, name, version}, exportedAt, collections: { "<name>": [ {id, data, version, createdBy, createdAt,
  updatedBy, updatedAt} ] } }` — same `DocView` shape as the Data API; ordered by collection, created. Streamed or
  capped (e.g. 100k documents → 413 with a message). File name: `<slug>-data.json` (several downloads don't
  collide in a Downloads folder) vs. the owner's literal `data.json` — Phase 2 confirms.
- Who: anyone signed in (they can already read all of it through the app).

## 3.5. Where the controls live
- Host page bar: a "⋯" menu (builders see all, others only downloads). App list cards stay clickable; an archived
  card shows "Restore" for builders.

## 3.6. Data API on archived apps
- Keep working (data is "kept"; U4 says the app still runs). Space check stays "app exists".

# 4. Acceptance criteria (draft)
1. A builder archives an app: it disappears from the list (visible under "Show archived"), its data is untouched,
   opening it shows the app with a Restore bar; Restore brings it back.
2. A builder deletes an app with data after typing its name: app, versions, documents and history are gone; the
   app document and Data API answer 404; deploying the same name later starts empty.
3. A builder replaces an app's HTML from a file: new version, same slug, same data, name/description unchanged.
4. Anyone signed in downloads `<title>.html` and the data JSON; the JSON contains every collection's documents.
5. Guests and users without the builder role cannot archive, restore, delete or replace (403), but can download.
6. All existing tests pass; new ones cover each operation, permissions and the cascade.

# 5. Risks
| Risk | Mitigation |
|---|---|
| Accidental irreversible delete | Typed-name confirmation, counts shown, server-side confirm parameter, download offered in the dialog |
| Any builder can delete anyone's app (U1) | Accepted; deletions logged (who, what, counts) at INFO; history of the decision in docs |
| Export of a large app eats memory | Cap with a clear message, or stream; measure on `test` |
| Changing `open()` breaks SHC-0014's plan | Note in the roadmap: auto-archive will also require Restore (or decide there) |
