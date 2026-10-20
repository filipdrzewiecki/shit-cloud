import { useNavigate } from 'react-router-dom'
import { ApiError } from '../api/client'
import { useReplaceHtml } from '../api/queries'

/**
 * The draft of a chat that changes an existing app goes into that app as its next version — never as a new app
 * by name, which a typo would turn into a second app.
 */
export function ReplaceBar({ slug, name, html }: { slug: string; name: string; html: string | null }) {
  const replace = useReplaceHtml(slug)
  const navigate = useNavigate()

  const error =
    replace.error instanceof ApiError && replace.error.status === 413
      ? 'This app is too large (max 5 MB).'
      : replace.error?.message

  return (
    <div className="deploy-bar">
      <button
        className="primary"
        disabled={!html || replace.isPending}
        onClick={() => html && replace.mutate(html, { onSuccess: () => navigate(`/apps/${slug}`) })}
      >
        {replace.isPending ? 'Replacing…' : `Replace HTML of “${name}”`}
      </button>
      {error && <span className="error-text">{error}</span>}
    </div>
  )
}
