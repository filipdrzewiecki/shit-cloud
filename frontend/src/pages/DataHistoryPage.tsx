import { useEffect, useState } from 'react'
import { Link, useParams, useSearchParams } from 'react-router-dom'
import { ApiError } from '../api/client'
import { useApps, useDataCollections, useDocHistory, useDocuments, useRestoreVersion } from '../api/queries'
import type { HistoryView } from '../api/types'
import { timeAgo } from '../lib/time'
import { fieldDiff, type FieldState } from './fieldDiff'

/** What replaced an earlier state, as a reader says it. */
const REPLACED_BY: Record<HistoryView['op'], string> = {
  update: 'changed',
  delete: 'removed',
  migrate: 'converted by a data change',
  undo: 'replaced by undoing a data change',
  restore: 'replaced by a restore',
}

const STATE_LABEL: Record<FieldState, string> = {
  same: '',
  differs: 'differs',
  removedSince: 'not there now',
  addedSince: 'added since',
}

function when(iso: string): string {
  return `${new Date(iso).toLocaleString()} (${timeAgo(iso)})`
}

function show(value: unknown): string {
  return value === undefined ? '—' : JSON.stringify(value)
}

function useDebounced<T>(value: T, ms: number): T {
  const [debounced, setDebounced] = useState(value)
  useEffect(() => {
    const timer = setTimeout(() => setDebounced(value), ms)
    return () => clearTimeout(timer)
  }, [value, ms])
  return debounced
}

/**
 * Data & history of one app (builders): its collections, their documents — removed ones too — and a document's
 * earlier states, each comparable with the document now and restorable. The documents are shown as stored (JSON):
 * the platform knows no schema. URL: `?c=<collection>&id=<document>&removed=1`.
 */
export function DataHistoryPage() {
  const { slug = '' } = useParams()
  const [params, setParams] = useSearchParams()
  const apps = useApps()
  const collections = useDataCollections(slug)
  const [filter, setFilter] = useState('')
  const prefix = useDebounced(filter.trim(), 300)

  const name = apps.data?.find((app) => app.slug === slug)?.name ?? slug
  const collection = params.get('c') ?? collections.data?.[0]?.collection ?? null
  const removed = params.get('removed') === '1'
  const selected = params.get('id')
  const documents = useDocuments(slug, collection, removed, prefix)

  const open = (changes: Record<string, string | null>) => {
    const next = new URLSearchParams(params)
    for (const [key, value] of Object.entries(changes)) {
      if (value === null) next.delete(key)
      else next.set(key, value)
    }
    setParams(next, { replace: true })
  }

  const items = documents.data?.pages.flatMap((page) => page.items) ?? []

  return (
    <section className="data-history">
      <div className="host-bar">
        <Link to={`/apps/${slug}`} className="back">
          ← {name}
        </Link>
        <strong>Data &amp; history</strong>
        <span className="muted">The app’s stored records as saved, with their earlier states (kept 90 days).</span>
      </div>

      {collections.isError && <p className="error-text">{collections.error.message}</p>}
      {collections.data?.length === 0 && <div className="empty">This app has not stored any data yet.</div>}
      {collections.data && collections.data.length > 0 && (
        <>
          <div className="data-toolbar">
            <div className="data-tabs" role="tablist">
              {collections.data.map((c) => (
                <button
                  key={c.collection}
                  type="button"
                  role="tab"
                  aria-selected={c.collection === collection}
                  className={c.collection === collection ? 'active' : ''}
                  onClick={() => open({ c: c.collection, id: null })}
                >
                  {c.collection} <span className="muted">{c.documents}</span>
                  {c.removed > 0 && <span className="muted"> · {c.removed} removed</span>}
                </button>
              ))}
            </div>
            <input
              className="data-filter"
              placeholder="Filter by id…"
              value={filter}
              onChange={(e) => setFilter(e.target.value)}
            />
            <label className="checkbox">
              <input
                type="checkbox"
                checked={removed}
                onChange={(e) => open({ removed: e.target.checked ? '1' : null, id: null })}
              />
              Removed records
            </label>
          </div>

          <div className="data-panels">
            <div className="data-list">
              {documents.isError && <p className="error-text">{documents.error.message}</p>}
              {documents.isSuccess && items.length === 0 && (
                <p className="muted">{removed ? 'No removed records.' : 'No records.'}</p>
              )}
              {items.map((doc) => (
                <button
                  key={doc.id}
                  type="button"
                  className={`data-row${doc.id === selected ? ' active' : ''}`}
                  onClick={() => open({ id: doc.id })}
                >
                  <span className="data-row-head">
                    <strong>{doc.id}</strong>
                    {doc.removed && <span className="badge muted-badge">removed</span>}
                    <span className="muted">
                      v{doc.version} · {doc.removed ? 'removed by ' : ''}
                      {doc.updatedBy} · {timeAgo(doc.updatedAt)}
                    </span>
                  </span>
                  <code className="data-preview">{doc.preview}</code>
                </button>
              ))}
              {documents.hasNextPage && (
                <button
                  type="button"
                  className="button"
                  disabled={documents.isFetchingNextPage}
                  onClick={() => void documents.fetchNextPage()}
                >
                  {documents.isFetchingNextPage ? 'Loading…' : 'Load more'}
                </button>
              )}
            </div>
            <div className="data-detail">
              {collection && selected ? (
                <DocHistoryPanel key={`${collection}/${selected}`} slug={slug} collection={collection} id={selected} />
              ) : (
                <div className="empty">Pick a record to see its earlier states.</div>
              )}
            </div>
          </div>
        </>
      )}
    </section>
  )
}

/** One record now and its earlier states, newest first, each restorable. */
function DocHistoryPanel({ slug, collection, id }: { slug: string; collection: string; id: string }) {
  const history = useDocHistory(slug, collection, id)
  const restore = useRestoreVersion(slug, collection, id)
  const [confirming, setConfirming] = useState<number | null>(null)
  const [restored, setRestored] = useState<number | null>(null)

  if (history.isPending) return <p className="muted">Loading…</p>
  if (history.isError) return <p className="error-text">{history.error.message}</p>
  const { current, versions, truncated } = history.data
  // States older than a data change have the old shape; restoring one brings that shape back.
  const firstMigration = versions.findIndex((v) => v.op === 'migrate')

  const conflict = restore.error instanceof ApiError && restore.error.status === 409
  const restoreError = conflict
    ? 'This record changed since you opened it. It is shown as it is now — check again before restoring.'
    : restore.error?.message

  return (
    <div className="doc-history">
      <h3>
        {collection}/{id}
      </h3>
      {current ? (
        <div className="doc-now">
          <div className="muted">
            Now: v{current.version} · by {current.updatedBy} · {when(current.updatedAt)}
          </div>
          <pre>{JSON.stringify(current.data, null, 2)}</pre>
        </div>
      ) : (
        <p className="doc-now muted">This record is removed. Restore one of its earlier states to bring it back.</p>
      )}
      {restored !== null && <p className="ok-text">Restored — it is now v{restored}.</p>}
      {restoreError && <p className="error-text">{restoreError}</p>}

      {versions.length === 0 && <p className="muted">No earlier states (history is kept 90 days).</p>}
      {versions.map((version, index) => (
        <details key={version.historyId} className="doc-version" open={index === 0}>
          <summary>
            <strong>v{version.version}</strong> · by {version.updatedBy} · {when(version.updatedAt)}
            <span className="muted">
              {' '}
              — {REPLACED_BY[version.op]} by {version.changedBy} {timeAgo(version.changedAt)}
            </span>
            {firstMigration !== -1 && index >= firstMigration && (
              <span className="badge muted-badge">before a data change</span>
            )}
          </summary>
          <table className="field-diff">
            <tbody>
              {fieldDiff(version.data, current?.data ?? null).map((row) => (
                <tr key={row.field} className={`field-${row.state}`}>
                  <th>{row.field}</th>
                  <td>
                    <code>{show(row.value)}</code>
                  </td>
                  <td className="muted">{row.state === 'same' ? '' : <>now: <code>{show(row.now)}</code></>}</td>
                  <td className="field-state">{STATE_LABEL[row.state]}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {confirming === version.historyId ? (
            <div className="doc-restore">
              <span>Make v{version.version} the current state{current ? '' : ' (the record comes back)'}?</span>
              <button
                type="button"
                className="button primary"
                disabled={restore.isPending}
                onClick={() =>
                  restore.mutate(
                    { historyId: version.historyId, expectedVersion: current?.version ?? 0 },
                    {
                      onSuccess: (doc) => {
                        setRestored(doc.version)
                        setConfirming(null)
                      },
                      onError: () => setConfirming(null),
                    },
                  )
                }
              >
                {restore.isPending ? 'Restoring…' : 'Yes, restore'}
              </button>
              <button type="button" className="button" onClick={() => setConfirming(null)}>
                Cancel
              </button>
            </div>
          ) : (
            <div className="doc-restore">
              <button
                type="button"
                className="button"
                onClick={() => {
                  restore.reset()
                  setRestored(null)
                  setConfirming(version.historyId)
                }}
              >
                ↺ Restore this version
              </button>
            </div>
          )}
        </details>
      ))}
      {truncated && <p className="muted">Only the latest {versions.length} earlier states are shown.</p>}
    </div>
  )
}
