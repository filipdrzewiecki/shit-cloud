import type { UIMessage } from 'ai'
import { describe, expect, it } from 'vitest'
import { fieldLines, lastMigration, migrationOf } from './migration'

const message = (id: string, ...parts: unknown[]) => ({ id, role: 'assistant', parts }) as UIMessage
const counts = { documents: 3, changed: 2, unchanged: 0, removed: 1, failed: 0 }
const part = (id: number) => ({ type: 'data-migration', id: 'migration', data: { id, space: 'team-calendar', counts } })

describe('migration', () => {
  it('reads a checked data change from a data-migration part, filling what is missing', () => {
    expect(migrationOf(part(7))).toEqual({
      id: 7,
      space: 'team-calendar',
      collections: [],
      summary: null,
      counts,
      byCollection: {},
      fields: {},
      examples: [],
      failures: [],
      readers: [],
    })
    expect(migrationOf({ type: 'data-draft', data: { html: '<html>' } })).toBeNull()
    expect(migrationOf({ type: 'data-migration', data: { id: 'x', space: 'a', counts } })).toBeNull()
    expect(migrationOf({ type: 'data-migration', data: null })).toBeNull()
  })

  it('finds the newest data change of a conversation', () => {
    const messages = [
      message('m1', part(1)),
      message('m2', { type: 'data-draft', data: { html: 'v2' } }, part(2)),
      message('m3', { type: 'text', text: 'Replace the app to apply it' }),
    ]
    expect(lastMigration(messages)?.id).toBe(2)
    expect(lastMigration([message('m', { type: 'text', text: 'x' })])).toBeNull()
  })

  it('says per collection which fields change', () => {
    expect(fieldLines({
      days: { kind: { added: 2, removed: 0, changed: 0 }, type: { added: 0, removed: 2, changed: 0 } },
      people: { name: { added: 0, removed: 0, changed: 1 } },
    })).toEqual(['days: kind added to 2; type removed from 2', 'people: name changed in 1'])
  })
})
