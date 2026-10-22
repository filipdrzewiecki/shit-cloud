import { describe, expect, it } from 'vitest'
import { fieldDiff } from './fieldDiff'

describe('fieldDiff', () => {
  it('marks each field of an earlier state against the document now', () => {
    expect(fieldDiff({ type: 'off', note: 'x', n: 1 }, { type: 'office', n: 1, kind: 'absence' })).toEqual([
      { field: 'type', value: 'off', now: 'office', state: 'differs' },
      { field: 'note', value: 'x', now: undefined, state: 'removedSince' },
      { field: 'n', value: 1, now: 1, state: 'same' },
      { field: 'kind', value: undefined, now: 'absence', state: 'addedSince' },
    ])
  })

  it('compares nested values as JSON, whatever the key order', () => {
    const rows = fieldDiff({ a: { x: 1, y: [1, 2] }, b: [1, 2] }, { a: { y: [1, 2], x: 1 }, b: [2, 1] })
    expect(rows.map((row) => row.state)).toEqual(['same', 'differs'])
    expect(fieldDiff({ a: null }, { a: {} })[0].state).toBe('differs')
  })

  it('counts every field as gone when the document is removed', () => {
    expect(fieldDiff({ type: 'off' }, null)).toEqual([{ field: 'type', value: 'off', now: undefined, state: 'removedSince' }])
  })
})
