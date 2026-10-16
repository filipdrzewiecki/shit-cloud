import { Link, useParams } from 'react-router-dom'
import { useApp } from '../api/queries'
import { AppFrame } from '../shell/AppFrame'

/**
 * The host page: an app name bar over the app's iframe. Later this is where the
 * postMessage bridge between the app's SDK and the Data API lives.
 */
export function AppHostPage() {
  const { slug = '' } = useParams()
  const app = useApp(slug)

  if (app.isPending) return <p className="muted">Loading…</p>
  if (app.isError) return <p className="error-text">Could not open app: {app.error.message}</p>

  const { app: summary, html } = app.data
  return (
    <section className="host">
      <div className="host-bar">
        <Link to="/" className="back">
          ← Apps
        </Link>
        <strong>{summary.name}</strong>
        <span className="muted">
          v{summary.version} · by {summary.owner}
        </span>
        <span className="spacer" />
        <span className="badge muted-badge" title="Each app will get its own origin: {slug}.apps.…">
          {summary.slug}.apps.shitcloud
        </span>
      </div>
      <AppFrame html={html} title={summary.name} />
    </section>
  )
}
