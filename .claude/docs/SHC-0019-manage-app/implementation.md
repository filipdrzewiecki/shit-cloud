# Implementation — SHC-0019: Manage a deployed app — remove, download, replace HTML

Input: `solution-design.md` (D1–D13, A1).

## Done (Tasks 1–9)

| Task | Result |
|---|---|
| 1 | `V2__archive.sql` (`archived_at`, `archived_by`); `AppRecord`/`AppSummary` + fields; `open()` only moves `last_used_at`; deploy by name clears the archive; dev seeder fills the columns for its archived sample |
| 2 | `AppRegistry`: `archive` (idempotent, first who/when kept), `restore`, `replaceHtml` (transaction: update + version row, 404 if missing, size check shared with deploy), `usage`, `delete` (confirm = slug, counts, history then app; INFO log); version insert shared with deploy |
| 3 | `DataService.export` + `ExportView`; `data.max-export-documents=100000` → 413 `TOO_LARGE`; reads with `list()` |
| 4 | `AppController`: archive, restore, `PUT …/html`, `DELETE …?confirm=`, usage, export (`Content-Disposition` `<slug>-data.json`); builder-only vs signed-in as designed; `DataExceptionHandler` now also covers `AppController` (export errors as `{code, message}`) |
| 5 | `client.ts`: `send()` shared, `api.download()` (Blob + name from `Content-Disposition`); types; mutations `useArchive/useRestore/useReplaceHtml/useDeleteApp`, `useAppUsage`; `lib/download.ts` (`saveFile`, `safeFileName`, `fileNameFromDisposition`) |
| 6 | `AppManageMenu` (⋯ Manage: downloads for all; replace/archive/restore/delete for builders), `ReplaceHtmlDialog` (file → `DraftFrame` preview → "Replace with version N"), `DeleteAppDialog` (counts, "download the data" link, type the name → "Delete forever", then back to the list), `shell/Modal` (native `<dialog>`); host page archived bar with Restore; app frame keyed by version so a replaced HTML reloads |
| 7 | App list: archived cards (under "Show archived") get "archived by …" + Restore for builders, outside the card link |
| 8 | `AGENTS.md` API table; builder guide "Deploying" section; product roadmap note on SHC-0014 |
| 9 | Local run: V2 applied, usage counts, export file name + JSON, guest 403 on all four builder actions, archived sample carries who/when. Builder actions locally only via ITs (no Auth0 locally) |

## Deviations from the design
- `DataExceptionHandler` scope widened to `AppController` (the export raises `DataException`); deploy's bad JSON
  now also answers `{code, message}` — still 400.
- Delete invalidates only the app list (removing the deleted app's own query while its page is still mounted
  triggered a pointless 404 refetch).
- `safeFileName` trims spaces after stripping dots/dashes ("a-b .html" otherwise).
- File name `<slug>-data.json` (A1) — the owner did not answer; trivial to change.

## Tests added / changed (Phase 4)
- New: `service/AppManagementIT` (12), `controller/AppManagementControllerIT` (9 incl. parameterised 403s),
  `data/ExportCapIT` (own context, cap 2), `frontend/src/lib/download.test.ts` (7)
- Changed: `AppRegistryIT` (open keeps the archive), `SchemaIT` (V2 applied)
- Result: 94 unit + 145 integration + 38 frontend, all green.
