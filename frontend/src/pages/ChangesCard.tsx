import type { RenderData } from '../chat'
import { MigrationCard } from './MigrationCard'
import { migrationOf } from './migration'

export type ChangeKind = 'replaced' | 'moved' | 'removed' | 'wont_work'

export interface Change {
  kind: ChangeKind
  what: string
  why: string
}

const MARK: Record<ChangeKind, { icon: string; label: string }> = {
  replaced: { icon: '⇄', label: 'replaced' },
  moved: { icon: '→', label: 'moved' },
  removed: { icon: '✖', label: 'removed' },
  wont_work: { icon: '⚠', label: 'won’t work' },
}

/** The change list in a `data-changes` part, or null for any other part. */
export function changesOf(part: { type: string; data?: unknown }): Change[] | null {
  if (part.type !== 'data-changes' || typeof part.data !== 'object' || part.data === null) return null
  const changes = (part.data as { changes?: unknown }).changes
  if (!Array.isArray(changes)) return null
  return changes
    .filter((c): c is Change => typeof c === 'object' && c !== null && (c as Change).kind in MARK && typeof (c as Change).what === 'string')
    .map((c) => ({ kind: c.kind, what: c.what, why: typeof c.why === 'string' ? c.why : '' }))
}

/** What the chat changed while adapting an uploaded file, one line per change. */
export function ChangesCard({ changes }: { changes: Change[] }) {
  return (
    <div className="changes-card">
      <div className="changes-head">
        <strong>Changes to the file</strong>
      </div>
      <ul>
        {changes.map((change, i) => (
          <li key={i} className={`change change-${change.kind}`}>
            <span className="change-mark" title={MARK[change.kind].label}>
              {MARK[change.kind].icon}
            </span>
            <span className="change-kind">{MARK[change.kind].label}</span>
            <span>
              {change.what}
              {change.why && <span className="muted"> — {change.why}</span>}
            </span>
          </li>
        ))}
      </ul>
    </div>
  )
}

/** For the chat panel: draws the Build page's own data parts (data changes and change lists). Module level, so stable. */
export const renderBuildData: RenderData = (part) => {
  const migration = migrationOf(part)
  if (migration) return <MigrationCard migration={migration} />
  const changes = changesOf(part)
  return changes ? <ChangesCard changes={changes} /> : null
}
