# Solution Design — SHC-0019: Manage a deployed app — remove, download, replace HTML

Input: `analysis.md` (decisions U1–U5).

---

# 1. Decisions carried from Phase 1

| # | Decision |
|---|---|
| U1 | Any builder archives, restores, deletes, replaces any app |
| U2 | Two downloads: `<title>.html` and the data JSON |
| U3 | No import of downloaded data |
| U4 | Archived app opens with a "This app is archived — Restore" bar; only Restore brings it back |
| U5 | Delete with data also deletes history |
| A1 | *Assumption (question unanswered):* the data file is named **`<slug>-data.json`**, not literally `data.json`, so several downloads don't overwrite each other |

# 2. Design decisions

| # | Decision | Why |
|---|---|---|
| D1 | **Flyway `V2__archive.sql`**: `apps.archived_at timestamptz`, `apps.archived_by text` (null when not archived). `archived` stays the flag | The bar can say who archived and when; SHC-0014 can reuse it with `archived_by = 'system'` |
| D2 | **`open()` no longer un-archives** (still moves `last_used_at`). A deploy **by name** of an archived app un-archives it (an explicit "make this live" action) | U4 |
| D3 | `AppSummary` gains `archivedAt`, `archivedBy` (so list and host page show them) | One shape for list and detail |
| D4 | **Endpoints** on `AppController` (slug in the path; 404 `No app …` when missing): <br>`POST /api/apps/{slug}/archive` → `AppSummary` (idempotent) <br>`POST /api/apps/{slug}/restore` → `AppSummary` (idempotent) <br>`PUT /api/apps/{slug}/html` `{html}` → `AppSummary` (new version; name, description, archive state and data unchanged; 413 over 5 MB) <br>`DELETE /api/apps/{slug}?confirm={slug}` → `{documents, versions, history}` deleted counts; `confirm` ≠ slug → 400 <br>`GET /api/apps/{slug}/usage` → `{documents, collections, versions}` (for the delete dialog) <br>`GET /api/apps/{slug}/export` → the data JSON, `Content-Disposition: attachment; filename="<slug>-data.json"` | A dedicated replace endpoint can never create a new app from a typo; the confirm parameter means a stray DELETE wipes nothing |
| D5 | **Permissions**: archive, restore, replace, delete → `hasRole('BUILDER')` (U1). usage, export → `isAuthenticated()` (everyone can already read the data through the app) | U1 |
| D6 | **Delete**: one transaction — count, `delete from doc_history where space = ?`, `delete from apps where slug = ?` (versions and docs cascade). Logged at INFO: slug, caller, counts | U5; the database already guarantees the cascade |
| D7 | **Export** (`DataService.export(slug)`): all documents of the space ordered by collection, created_at, id → `{app: {slug, name, version}, exportedAt, collections: {"<name>": [DocView…]}}`. Read with `.list()` (never `stream()` — the SHC-0018 leak). Cap `data.max-export-documents=100000` → 413 `TOO_LARGE` | Same `DocView` shape as the Data API; bounded memory |
| D8 | **HTML download** is client-side from `GET /api/apps/{slug}` (`html` is already in the detail): file name = app name with `\ / : * ? " < > |` replaced by `-`, + `.html` | No new endpoint; the name the user sees |
| D9 | **Data download** goes through `api/client.ts` (the token must be attached, so not a plain link): new `download(path)` → `Blob` + file name from `Content-Disposition` | Keeps the one-place-for-tokens rule |
| D10 | **Host page "⋯ Manage" menu** (top bar): everyone sees *Download HTML*, *Download data*; builders also *Replace HTML…*, *Archive* / *Restore*, *Delete with data…*. Archived app → yellow bar "This app is archived by X on DATE" + *Restore* (builders) | U4; controls next to the app they act on |
| D11 | **Replace dialog**: file picker → `DraftFrame` preview → *Replace* (shows the new version number after). **Delete dialog**: counts from `usage`, "Download data first" button, type the app's **name** to enable *Delete forever* | Irreversible action is deliberate and informed |
| D12 | **App list**: archived cards (under "Show archived") get a *Restore* button for builders (outside the card link) | Restore without opening |
| D13 | Data API and the app document keep working for archived apps; deleted apps 404 everywhere | U4 — "data is kept, the app still runs" |

# 3. Known gaps (accepted)
- No undo for delete (U5) — export first is the safety net, offered in the dialog.
- Any builder can delete any app (U1); the INFO log is the only trace.
- No import of exported data (U3).
- SHC-0014's automatic archiving will also need Restore (D2) — noted in the product roadmap.

# 4. Backend
- `V2__archive.sql`; `AppRecord`/`AppSummary` + `archivedAt`/`archivedBy`; `AppRegistry`: `archive`, `restore`,
  `replaceHtml`, `delete`, `usage`; `open` change; deploy clears the archive columns.
- `DataService.export`, `ExportView` record, `DataProperties.maxExportDocuments` (`data.max-export-documents`).
- `AppController`: the six endpoints (D4, D5).

# 5. Frontend
- `api/types.ts` (`AppSummary` fields, `AppUsage`, `DeleteResult`), `api/client.ts` (`download`), `api/queries.ts`
  (mutations: archive, restore, replace, delete; usage query), `lib/download.ts` (save a Blob, safe file names).
- `pages/AppHostPage.tsx`: Manage menu, archived bar; new `pages/ReplaceHtmlDialog.tsx`, `pages/DeleteAppDialog.tsx`.
- `pages/AppListPage.tsx`: Restore on archived cards.

# 6. Acceptance criteria
1. Archive → gone from the list (shown under "Show archived" with Restore); data untouched; opening shows the app
   with the archived bar; Restore → back in the list.
2. Delete with data (typed name) → app, versions, documents, history gone; app document and Data API 404; the same
   name deployed later starts empty.
3. Replace HTML from a file → new version, same slug, same data, name/description unchanged.
4. Download HTML → `<name>.html`; Download data → `<slug>-data.json` with every collection.
5. Guests / non-builders: 403 on archive, restore, replace, delete; downloads work.
6. All existing tests pass; new tests below pass.

# 7. Implementation tasks (Phase 3)

- Task 1 — `V2__archive.sql`; `AppRecord`/`AppSummary` archive fields; `open()` stops un-archiving; deploy clears the archive
- Task 2 — `AppRegistry`: `archive`, `restore`, `replaceHtml`, `delete` (counts, history, log), `usage`
- Task 3 — `DataService.export` + `ExportView` + cap property
- Task 4 — `AppController` endpoints + permissions
- Task 5 — Frontend API: types, `client.download`, mutations/queries, `lib/download.ts`
- Task 6 — Host page: Manage menu, archived bar + Restore, Replace HTML dialog, Delete dialog
- Task 7 — App list: Restore on archived cards
- Task 8 — `AGENTS.md` API table; product roadmap note on SHC-0014; builder guide (deploy section)
- Task 9 — Local end-to-end check (API with curl, frontend build)
- Task 10 — Deploy (only on the user's order to commit/push) + acceptance criteria

Phase 4 tests:
- `AppRegistryIT`: archive/restore idempotent + who/when; open keeps archive; deploy by name un-archives; replace
  keeps name/description/data/archive and bumps version; delete removes app, versions, docs, history and nothing of
  other apps; same name after delete starts at version 1 with no data; usage counts.
- `AppControllerIT`: each endpoint's happy path; 403 for guest and role-less user on the four builder actions;
  400 on a wrong `confirm`; 404 on a missing app; export content and `Content-Disposition`; 413 on replace too large.
- `DataServiceIT`: export grouping/order, cap → `TOO_LARGE`.
- `SchemaIT`: V2 applied.
- `ControllerAuthorizationIT` keeps passing.
- Frontend (Vitest): `lib/download.ts` file-name sanitising and `Content-Disposition` parsing.

# 8. Trade-offs
| Choice | Cost accepted |
|---|---|
| Archive columns instead of a separate table | Two nullable columns on `apps` |
| Export built in memory (capped) | Very large apps get 413 until streaming is needed |
| Typed-name confirmation | One more step for a deliberate action — intended |
