import { describe, expect, it } from 'vitest'
import type { DependencyView } from '../api/types'
import { otherUsers } from './usedBy'

function row(app: string, collection: string, mode: 'read' | 'write' = 'read', archived = false): DependencyView {
  return {
    app,
    appName: app.toUpperCase(),
    archived,
    collection,
    mode,
    firstSeenAt: '2026-10-01T00:00:00Z',
    lastSeenAt: '2026-10-21T00:00:00Z',
  }
}

describe('otherUsers', () => {
  it('leaves out the app itself and lists each other app once, in the order given', () => {
    const rows = [
      row('team-calendar', 'marks', 'write'),
      row('office-report', 'marks'),
      row('lunch-stats', 'people', 'read', true),
      row('office-report', 'people'),
      row('office-report', 'marks', 'write'),
    ]

    expect(otherUsers(rows, 'team-calendar')).toEqual([
      { slug: 'office-report', name: 'OFFICE-REPORT', archived: false, collections: ['marks', 'people'] },
      { slug: 'lunch-stats', name: 'LUNCH-STATS', archived: true, collections: ['people'] },
    ])
  })

  it('is empty when only the app itself used its data', () => {
    expect(otherUsers([row('team-calendar', 'marks')], 'team-calendar')).toEqual([])
    expect(otherUsers([], 'team-calendar')).toEqual([])
  })
})
