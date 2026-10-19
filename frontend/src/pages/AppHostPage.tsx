import { useRef } from 'react'
import { Link, useParams } from 'react-router-dom'
import { useApp } from '../api/queries'
import type { AppDetail } from '../api/types'
import { AppFrame } from '../shell/AppFrame'
import { useAppBridge } from '../shell/useAppBridge'

/**
 * The host page: an app name bar over the app's iframe, and the bridge that
 * answers the app's SDK calls with the Data API.
 */
export function AppHostPage() {
  const { slug = '' } = useParams()
  const app = useApp(slug)

  if (app.isPending) return <p className="muted">Loading…</p>
  if (app.isError) return <p className="error-text">Could not open app: {app.error.message}</p>

  return <HostedApp slug={slug} detail={app.data} />
}

function HostedApp({ slug, detail }: { slug: string; detail: AppDetail }) {
  const frameRef = useRef<HTMLIFrameElement>(null)
  const { app: summary, frameUrl } = detail
  useAppBridge(frameRef, frameUrl, slug)

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
        <span className="badge muted-badge" title="The app runs on its own origin, apart from ShitCloud">
          {new URL(frameUrl).host}
        </span>
        <a href={frameUrl} target="_blank" rel="noopener noreferrer">
          Open in new tab
        </a>
      </div>
      <AppFrame ref={frameRef} src={frameUrl} title={summary.name} />
    </section>
  )
}
