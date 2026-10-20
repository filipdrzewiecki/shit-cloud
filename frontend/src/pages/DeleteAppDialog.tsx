import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAppUsage, useDeleteApp } from '../api/queries'
import type { AppSummary } from '../api/types'
import { Modal } from '../shell/Modal'

/**
 * Deletes an app with everything it holds. Irreversible, so the dialog says what
 * goes, offers the data download first, and asks for the app's name.
 */
export function DeleteAppDialog({
  app,
  onDownloadData,
  onClose,
}: {
  app: AppSummary
  onDownloadData: () => void
  onClose: () => void
}) {
  const [typed, setTyped] = useState('')
  const usage = useAppUsage(app.slug, true)
  const remove = useDeleteApp(app.slug)
  const navigate = useNavigate()
  const confirmed = typed.trim() === app.name.trim()

  return (
    <Modal title={`Delete “${app.name}” with its data`} onClose={onClose}>
      <p>This removes the app and everything it holds, for everyone. It cannot be undone.</p>
      <ul className="delete-list">
        {usage.data ? (
          <>
            <li>
              <strong>{usage.data.documents}</strong> documents in <strong>{usage.data.collections}</strong> collections
              {usage.data.documents > 0 && ', with their change history'}
            </li>
            <li>
              <strong>{usage.data.versions}</strong> versions of the HTML
            </li>
          </>
        ) : (
          <li className="muted">{usage.isError ? 'Could not count the data.' : 'Counting…'}</li>
        )}
      </ul>
      <p className="muted">
        To keep a copy, <button type="button" className="link-button inline" onClick={onDownloadData}>download the data</button>{' '}
        first. To hide the app but keep its data, archive it instead.
      </p>
      <label className="confirm-label">
        Type <strong>{app.name}</strong> to confirm
        <input value={typed} onChange={(e) => setTyped(e.target.value)} autoComplete="off" />
      </label>
      <div className="modal-actions">
        {remove.isError && <span className="error-text">{remove.error.message}</span>}
        <button type="button" className="button" onClick={onClose}>
          Cancel
        </button>
        <button
          type="button"
          className="button danger"
          disabled={!confirmed || remove.isPending}
          onClick={() => remove.mutate(undefined, { onSuccess: () => navigate('/', { replace: true }) })}
        >
          {remove.isPending ? 'Deleting…' : 'Delete forever'}
        </button>
      </div>
    </Modal>
  )
}
