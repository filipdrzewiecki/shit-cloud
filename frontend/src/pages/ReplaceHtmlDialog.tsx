import { type ChangeEvent, useState } from 'react'
import { ApiError } from '../api/client'
import { useReplaceHtml } from '../api/queries'
import type { AppSummary } from '../api/types'
import { DraftFrame } from '../shell/DraftFrame'
import { Modal } from '../shell/Modal'

/**
 * Puts new HTML into an existing app as its next version. The app keeps its
 * name, address and data — only the page people see changes.
 */
export function ReplaceHtmlDialog({ app, onClose }: { app: AppSummary; onClose: () => void }) {
  const [html, setHtml] = useState<string | null>(null)
  const [fileName, setFileName] = useState('')
  const replace = useReplaceHtml(app.slug)

  const pick = async (e: ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0]
    if (!file) return
    setFileName(file.name)
    setHtml(await file.text())
  }

  const error =
    replace.error instanceof ApiError && replace.error.status === 413
      ? 'This file is too large (max 5 MB).'
      : replace.error?.message

  return (
    <Modal title={`Replace the HTML of “${app.name}”`} onClose={onClose}>
      <p className="muted">
        The new file becomes version {app.version + 1}. The app's data, name and address stay as they are.
      </p>
      <label className="dropzone">
        <input type="file" accept=".html,.htm,text/html" onChange={pick} />
        {fileName ? (
          <span>
            <strong>{fileName}</strong> — pick another file to change it
          </span>
        ) : (
          <span>Click to choose an .html file</span>
        )}
      </label>
      {html && (
        <div className="modal-preview">
          <DraftFrame html={html} title="New HTML preview" />
        </div>
      )}
      <div className="modal-actions">
        {replace.isError && <span className="error-text">{error}</span>}
        <button type="button" className="button" onClick={onClose}>
          Cancel
        </button>
        <button
          type="button"
          className="button primary"
          disabled={!html || replace.isPending}
          onClick={() => html && replace.mutate({ html }, { onSuccess: onClose })}
        >
          {replace.isPending ? 'Replacing…' : `Replace with version ${app.version + 1}`}
        </button>
      </div>
    </Modal>
  )
}
