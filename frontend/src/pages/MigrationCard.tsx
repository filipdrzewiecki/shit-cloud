import { createContext, useContext } from 'react'
import { fieldLines, type MigrationProposal } from './migration'

/** The id of the newest proposal in the open conversation; older cards say they were replaced. */
export const LatestMigration = createContext<number | null>(null)

function plural(n: number, word: string): string {
  return `${n} ${word}${n === 1 ? '' : 's'}`
}

function json(value: unknown): string {
  return JSON.stringify(value, null, 2)
}

/** A checked data change inside the conversation: what it does to the stored documents, before anyone applies it. */
export function MigrationCard({ migration }: { migration: MigrationProposal }) {
  const latest = useContext(LatestMigration)
  const { counts } = migration
  const replaced = latest !== null && latest !== migration.id

  return (
    <div className={`migration-card${replaced ? ' replaced' : ''}`}>
      <div className="migration-head">
        <strong>Data change</strong>
        {migration.summary && <span> — {migration.summary}</span>}
      </div>
      {replaced ? (
        <p className="muted">Replaced by a newer proposal below.</p>
      ) : (
        <>
          <p>
            {plural(counts.documents, 'document')} in {migration.collections.join(', ')}: {counts.changed} changed,{' '}
            {counts.unchanged} unchanged
            {counts.removed > 0 && <strong className="error-text">, {counts.removed} removed</strong>}
            {counts.failed > 0 && <strong className="error-text">, {counts.failed} failed</strong>}.
          </p>
          {fieldLines(migration.fields).length > 0 && (
            <ul className="migration-fields">
              {fieldLines(migration.fields).map((line) => (
                <li key={line}>{line}</li>
              ))}
            </ul>
          )}
          {migration.failures.length > 0 && (
            <ul className="migration-failures error-text">
              {migration.failures.map((f) => (
                <li key={`${f.collection}/${f.id}`}>
                  {f.collection}/{f.id}: {f.error}
                </li>
              ))}
            </ul>
          )}
          {migration.readers.length > 0 && (
            <p className="migration-readers">
              ⚠ Also read by{' '}
              {migration.readers.map((r) => `${r.name} (${r.collections.join(', ')})`).join(', ')} — they may break.
            </p>
          )}
          {migration.examples.length > 0 && (
            <details>
              <summary>{plural(migration.examples.length, 'example')}: before → after</summary>
              {migration.examples.map((example) => (
                <div key={`${example.collection}/${example.id}`} className="migration-example">
                  <div className="muted">
                    {example.collection}/{example.id}
                  </div>
                  <pre>{json(example.before)}</pre>
                  <pre>{example.after === null ? '(removed)' : json(example.after)}</pre>
                </div>
              ))}
            </details>
          )}
          <p className="muted">
            {counts.failed > 0
              ? 'It cannot be applied while documents fail.'
              : 'Nothing is changed yet: it is applied together with the draft when you replace the app’s HTML.'}
          </p>
        </>
      )}
    </div>
  )
}
