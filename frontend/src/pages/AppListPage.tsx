import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useApps, useMe, useRestore } from '../api/queries'
import type { AppSummary } from '../api/types'
import { timeAgo } from '../lib/time'

export function AppListPage() {
  const apps = useApps()
  const me = useMe()
  const [search, setSearch] = useState('')
  const [showArchived, setShowArchived] = useState(false)

  const needle = search.trim().toLowerCase()
  const visible = (apps.data ?? []).filter(
    (app) =>
      (showArchived || !app.archived) &&
      (needle === '' || `${app.name} ${app.description ?? ''} ${app.owner}`.toLowerCase().includes(needle)),
  )
  const archivedCount = (apps.data ?? []).filter((app) => app.archived).length

  return (
    <section>
      <div className="page-head">
        <div>
          <h1>Apps</h1>
          <p className="muted">
            Simple HTML Internal Tools your team built. Disposable apps, durable data.
          </p>
        </div>
        {me.data?.builder && (
          <div className="actions">
            <Link to="/upload" className="button">
              Upload HTML
            </Link>
            <Link to="/build" className="button primary">
              + New app with AI
            </Link>
          </div>
        )}
      </div>

      <div className="toolbar">
        <input
          className="search"
          placeholder="Search apps, descriptions, owners…"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
        />
        <label className="checkbox">
          <input type="checkbox" checked={showArchived} onChange={(e) => setShowArchived(e.target.checked)} />
          Show archived ({archivedCount})
        </label>
      </div>

      {apps.isPending && <p className="muted">Loading…</p>}
      {apps.isError && <p className="error-text">Could not load apps: {apps.error.message}</p>}

      <div className="grid">
        {visible.map((app) => (
          <div key={app.slug} className="card-wrap">
            <Link to={`/apps/${app.slug}`} className={`card ${app.archived ? 'archived' : ''}`}>
              <div className="card-title">
                <span>{app.name}</span>
                {app.archived && <span className="badge muted-badge">archived</span>}
              </div>
              <p className="card-desc">{app.description || <i>No description</i>}</p>
              <div className="card-meta">
                <span>{app.owner}</span>
                <span>·</span>
                <span>v{app.version}</span>
                <span>·</span>
                <span>used {timeAgo(app.lastUsedAt)}</span>
              </div>
            </Link>
            {app.archived && me.data?.builder && <RestoreButton app={app} />}
          </div>
        ))}
        {apps.isSuccess && visible.length === 0 && <p className="muted">No apps match.</p>}
      </div>
    </section>
  )
}

/** Outside the card's link: a button inside a link is not valid HTML, and a click must not open the app. */
function RestoreButton({ app }: { app: AppSummary }) {
  const restore = useRestore(app.slug)
  return (
    <div className="card-restore">
      {app.archivedBy && <span className="muted">archived by {app.archivedBy}</span>}
      <button type="button" className="button" disabled={restore.isPending} onClick={() => restore.mutate()}>
        {restore.isPending ? 'Restoring…' : '↺ Restore'}
      </button>
      {restore.isError && <span className="error-text">{restore.error.message}</span>}
    </div>
  )
}
