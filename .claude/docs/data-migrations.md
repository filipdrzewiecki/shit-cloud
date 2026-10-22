# Data migrations (AI-written, in place) — reference

When the build chat changes an existing app and its stored documents need a new shape, the model writes a JS
transform, the platform checks it over every document, the builder approves it by replacing the HTML, and the data
is converted in place. The old documents are kept for 30 days for undo. The code is the source of truth; this file
keeps the decisions, the flow and the traps.

## Decisions (owner, SHC-0011)

| | Decision |
|---|---|
| Where | **In place**, in the app's own space. This departs from handoff §7.2's "old space → new space": here space = slug, and a rework keeps the slug. "Old space read-only 30 days" became a frozen copy (`migration_docs`) plus undo. |
| When | **Together with Replace HTML**, in one transaction: the new HTML never meets old data. |
| Failures | **All or nothing.** A check with failures can't be applied. Apply runs the transform again and aborts on any failure. |
| Power | `migrate(doc, collection)` returns new fields or `null` (remove). Same collection, same id. |
| Engine | GraalJS Community 25.0.5 on the plain JDK 21 (interpreted). |
| Undo | For `migration.copy-ttl` (30 d): documents of the migrated collections and the HTML from before come back. Later writes there are replaced (their states stay in `doc_history`). The copy is downloadable. |
| Limits | `migration.max-documents` 100 000; `migration.timeout` 60 s per run; `migration.statement-limit` 1 000 000 per document. |
| Scope | Only the app being changed, only in a rework conversation (`targetSlug`). |

## Flow

```
chat (rework of X): … edit_draft (HTML reads the new shape) → propose_migration({code, summary, collections?})
  MigrationService.propose: paged walk (500, keyset on docs.id) through MigrationSandbox, nothing written
    → migrations row 'proposed' + data-migration part (fixed id): counts per collection, field changes,
      ≤ 3 before/after, ≤ 5 failures, readers from app_deps
Build page: MigrationCard in the chat (ChatPanel renderData) · ReplaceBar "Convert the data" (on by default)
  PUT /api/apps/X/html {html, migrationId} → MigrationService.applyWithHtml, one transaction:
    exclusive space lock → proposal FOR UPDATE (this space, 'proposed', 0 failures, else 409 MIGRATION_STALE)
    → replace HTML (413 before any document is touched) → copy into migration_docs
    → walk again: rewrite/erase with history op 'migrate'; any failure or timeout → 409 MIGRATION_FAILED, all rolled back
    → row 'applied', result counts, html_version_before/after, copy_until
App page (builders): Manage → "Undo data change…" / "Data before the change" while the latest applied change is undoable
  POST /api/migrations/{id}/undo — set-based SQL under the exclusive lock, history op 'undo';
  re-created documents continue after the highest version history knows; HTML of html_version_before as a new version
ExpiryJob: copies past copy_until dropped (audit row stays, copy_until → null); unapplied proposals after proposal-ttl
```

## Sandbox (`migration/MigrationSandbox`)

- `SandboxPolicy.CONSTRAINED` on **both** the engine and the context; GraalJS refuses a mismatch.
- Host access must be `HostAccess.CONSTRAINED`. `HostAccess.NONE` is refused under CONSTRAINED, because it allows
  mutable target mappings. No host object ever crosses anyway: documents go in as JSON text and come out through
  `JSON.stringify`.
- `Java`, `Polyglot`, `require` and `process` are undefined. `load`/`loadWithNewGlobal` exist and are deleted in a
  prelude. IO and threads are off.
- **After the statement limit is hit, the context is dead.** Its `close()` throws again (caught), so the run builds a
  fresh context and only that document fails.
- The watchdog `close(true)`s the context at the run's deadline. That surfaces as `isCancelled()` or as an
  `IllegalStateException` on a closed context; both mean "out of time" for the whole run.
- **There is no heap limit in CE** (that needs Oracle GraalVM). Memory is bounded by the input (one document,
  ≤ 256 KB) and the statement limit; accepted for builder-only use.
- The engine is built lazily, with `engine.WarnInterpreterOnly=false`.
- The transform's output goes through `DataService.cleanData`, the same rules as an app write: `id`/`_` keys
  dropped, size, depth, field names. Equal after that = unchanged (no write).

## Concurrency

- Every data write (`set`, `add` — now in a transaction — and `remove`) takes
  `pg_advisory_xact_lock_shared(0x5CDA7A, hashtext(space))`.
- Apply and undo take the exclusive `pg_advisory_xact_lock` on the same key. Writers to that app wait (SDK calls
  may hit their 15 s `TIMEOUT`); writers don't wait for each other. A hash collision between two spaces only makes
  them wait for each other.

## Known gaps

- A reopened conversation whose proposal was already applied still offers "convert". Replace then answers 409
  `MIGRATION_STALE` ("was applied already"), and the builder unticks the box.
- A number stored as `1.0` comes back from JS as `1`: such a document counts as changed and is rewritten as `1`.
- Non-deterministic transforms (`Date.now()`, `Math.random()`) can differ between the check and the apply. The
  prompt forbids them; this is not detected.
- Before/after examples are real documents sent to the model vendor, as with `sample_docs`.
- Other apps reading the space see the new shape at once. The card and the model warn (from `app_deps`); nothing is
  blocked.

## Testing

`MigrationSandboxTest` (real engine), `MigrationServiceIT` (check, apply, rollback, stale, undo, copy, writers
waiting on the lock), `MigrationControllerIT`, `ProposeMigrationToolTest`, `BuildChatControllerIT` (card + refusal in
a new-app conversation), `ExpiryJobIT`, `SchemaIT`; frontend `pages/migration.test.ts`, `chat/toolLabels.test.ts`.
