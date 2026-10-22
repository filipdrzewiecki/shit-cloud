import { describe, expect, it } from 'vitest'
import { changesOf } from './ChangesCard'

describe('changesOf', () => {
  it('reads the change list of a data-changes part, dropping malformed items', () => {
    expect(changesOf({
      type: 'data-changes',
      data: { changes: [
        { kind: 'replaced', what: 'jQuery by plain JavaScript', why: 'no CDNs' },
        { kind: 'wont_work', what: 'Weather box' },
        { kind: 'fixed', what: 'unknown kind' },
        'text',
      ] },
    })).toEqual([
      { kind: 'replaced', what: 'jQuery by plain JavaScript', why: 'no CDNs' },
      { kind: 'wont_work', what: 'Weather box', why: '' },
    ])
  })

  it('ignores other parts', () => {
    expect(changesOf({ type: 'data-draft', data: { html: '<html>' } })).toBeNull()
    expect(changesOf({ type: 'data-changes', data: { changes: 'x' } })).toBeNull()
  })
})
