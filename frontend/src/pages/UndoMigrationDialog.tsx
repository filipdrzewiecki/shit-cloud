import { useUndoMigration } from '../api/queries'
import type { AppSummary, MigrationView } from '../api/types'
import { Modal } from '../shell/Modal'

function day(iso: string | null): string {
  return iso ? new Date(iso).toLocaleDateString() : '—'
}

/**
 * Puts an app back as it was before the build chat's latest data change: the documents and the HTML. Documents
 * written since are replaced, so the dialog says so; their states stay in the change history.
 */
export function UndoMigrationDialog({
  app,
  migration,
  onDownloadCopy,
  onClose,
}: {
  app: AppSummary
  migration: MigrationView
  onDownloadCopy: () => void
  onClose: () => void
}) {
  const undo = useUndoMigration()

  return (
    <Modal title={`Undo the data change of “${app.name}”`} onClose={onClose}>
      <p>
        {migration.summary ?? 'Data change'} — applied {day(migration.appliedAt)} by {migration.appliedBy}
        {migration.result && <> to {migration.result.documents} documents</>} in {migration.collections.join(', ')}.
      </p>
      <ul className="delete-list">
        <li>The documents of {migration.collections.join(', ')} go back to how they were right before the change.</li>
        <li>
          <strong>Anything written there since is replaced</strong>; those states stay in the change history for 90
          days.
        </li>
        {migration.htmlVersionBefore !== null && (
          <li>The app’s HTML goes back to version {migration.htmlVersionBefore} (as a new version).</li>
        )}
      </ul>
      <p className="muted">
        Possible until {day(migration.copyUntil)}.{' '}
        <button type="button" className="link-button inline" onClick={onDownloadCopy}>
          Download the data from before the change
        </button>
      </p>
      <div className="modal-actions">
        {undo.isError && <span className="error-text">{undo.error.message}</span>}
        <button type="button" className="button" onClick={onClose}>
          Cancel
        </button>
        <button
          type="button"
          className="button danger"
          disabled={undo.isPending}
          onClick={() => undo.mutate(migration.id, { onSuccess: onClose })}
        >
          {undo.isPending ? 'Undoing…' : 'Undo the data change'}
        </button>
      </div>
    </Modal>
  )
}
