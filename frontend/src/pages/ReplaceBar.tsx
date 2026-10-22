import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { ApiError } from '../api/client'
import { useReplaceHtml } from '../api/queries'
import type { MigrationProposal } from './migration'

function replaceError(error: Error | null): string | undefined {
  if (error instanceof ApiError && error.status === 413) return 'This app is too large (max 5 MB).'
  return error?.message
}

/**
 * The draft of a chat that changes an existing app goes into that app as its next version — never as a new app
 * by name, which a typo would turn into a second app. A data change the chat checked is applied in the same step,
 * so the new HTML never meets the old data shape; nothing changes when either fails.
 */
export function ReplaceBar({
  slug,
  name,
  html,
  migration,
}: {
  slug: string
  name: string
  html: string | null
  migration: MigrationProposal | null
}) {
  const replace = useReplaceHtml(slug)
  const navigate = useNavigate()
  const [convert, setConvert] = useState(true)

  const applicable = migration !== null && migration.counts.failed === 0
  const converting = applicable && convert
  const documents = migration?.counts.documents ?? 0

  return (
    <div className="deploy-bar">
      {applicable && (
        <label className="checkbox">
          <input type="checkbox" checked={convert} onChange={(e) => setConvert(e.target.checked)} />
          Convert the data ({documents} {documents === 1 ? 'document' : 'documents'})
        </label>
      )}
      {migration !== null && !applicable && (
        <span className="error-text">The data change fails on {migration.counts.failed} documents — ask the chat to fix it.</span>
      )}
      <button
        className="primary"
        disabled={!html || replace.isPending}
        onClick={() =>
          html &&
          replace.mutate(
            { html, migrationId: converting ? migration.id : null },
            { onSuccess: () => navigate(`/apps/${slug}`) },
          )
        }
      >
        {replace.isPending
          ? converting ? 'Replacing and converting…' : 'Replacing…'
          : converting ? `Replace HTML of “${name}” and convert the data` : `Replace HTML of “${name}”`}
      </button>
      {replace.error && <span className="error-text">{replaceError(replace.error)}</span>}
    </div>
  )
}
