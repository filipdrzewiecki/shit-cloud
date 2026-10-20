# Managing a deployed app — archive, restore, replace, delete, download (reference)

From SHC-0019 (2026-10-20). Endpoints are listed in `.claude/AGENTS.md`; this file keeps the rules and why.

## Owner decisions
- **Any builder** may archive, restore, replace and delete **any** app (as deploying already works). Usage and the
  data export are open to everyone signed in — they can read the data through the app anyway.
- Download = **two buttons**: the HTML as `<app name>.html`, the data as `<slug>-data.json`. **No import** yet.
- **Delete with data also deletes the documents' history** — everything goes at once.
- An archived app **still opens** (it runs, its data is there) with a yellow bar "archived by X on DATE" and
  *Restore*; only Restore (or a deploy by name) brings it back to the list.

## Rules
- Archive: `apps.archived` + `archived_at`/`archived_by` (Flyway V2). Opening an app moves `last_used_at` but **never
  un-archives** it; a deploy **by name** does (an explicit "make it live"). The Data API and the app document keep
  working for archived apps. The automatic 30-day archiving (SHC-0014) will need Restore too.
- Replace HTML is its own endpoint (`PUT /api/apps/{slug}/html`): a typo can never create a new app. Name,
  description, archive state and data stay; the version goes up.
- Delete needs `?confirm=<slug>` (a stray DELETE wipes nothing) and, in the GUI, the app's **name** typed after the
  counts from `/usage` and a "Download data first" button. One transaction: delete `doc_history` rows of the space
  explicitly (that table has **no FK** to `apps`, on purpose — elsewhere history outlives its document), then the
  app row; versions and documents cascade. Logged at INFO with the counts. Deleted apps answer 404 everywhere; a
  new deploy of the same name starts empty.
- Export: `{app: {slug, name, version}, exportedAt, collections: {name: [DocView…]}}` ordered by collection,
  created_at, id; read with `.list()` (never `stream()`, see `postgres.md`); capped at
  `data.max-export-documents` (100 000) → 413 `TOO_LARGE` instead of a stalled server. `DataExceptionHandler` covers
  `AppController` too, so these errors are `{code, message}`.
- Downloads go through `api/client.ts#download` (the token must be attached, so not a plain link); the file name
  comes from `Content-Disposition`. The HTML download is built client-side from `GET /api/apps/{slug}`, with
  `\ / : * ? " < > |` replaced by `-` (`lib/download.ts#safeFileName`).
- GUI: host page "⋯ Manage" menu (everyone: the downloads; builders: Replace…, Archive/Restore, Delete…); Replace
  previews the file in `DraftFrame` first; archived cards in the list (under "Show archived") have Restore.
  After a delete only the app list is invalidated — refetching the deleted app's own query while its page is
  still mounted gave a pointless 404.

## Tests
`service/AppManagementIT`, `controller/AppManagementControllerIT` (403s per role), `data/ExportCapIT` (own context,
cap 2), `frontend/src/lib/download.test.ts`; `AppRegistryIT` (open keeps the archive), `SchemaIT` (V2).
