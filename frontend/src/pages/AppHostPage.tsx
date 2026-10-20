import { useRef } from 'react'
import { Link, useParams } from 'react-router-dom'
import { useApp, useMe, useRestore } from '../api/queries'
import type { AppDetail } from '../api/types'
import { AppFrame } from '../shell/AppFrame'
import { useAppBridge } from '../shell/useAppBridge'
import { AppManageMenu } from './AppManageMenu'

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
  const builder = useMe().data?.builder ?? false
  const restore = useRestore(slug)
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
        <AppManageMenu detail={detail} builder={builder} />
      </div>
      {summary.archived && (
        <div className="archived-bar">
          <span>
            🗄 This app is archived
            {summary.archivedBy && <> by {summary.archivedBy}</>}
            {summary.archivedAt && <> on {new Date(summary.archivedAt).toLocaleDateString()}</>} — it is hidden from
            the app list; its data is kept.
          </span>
          {builder && (
            <button type="button" className="button" disabled={restore.isPending} onClick={() => restore.mutate()}>
              {restore.isPending ? 'Restoring…' : '↺ Restore'}
            </button>
          )}
          {restore.isError && <span className="error-text">{restore.error.message}</span>}
        </div>
      )}
      {/* Keyed by version: a replaced HTML lives at the same address, so the frame must reload to show it. */}
      <AppFrame key={summary.version} ref={frameRef} src={frameUrl} title={summary.name} />
    </section>
  )
}
