import { describe, expect, it } from 'vitest'
import { toolLine } from './toolLabels'

describe('toolLine', () => {
  it('describes save_draft without its input (a whole app)', () => {
    expect(toolLine({ type: 'tool-save_draft', state: 'input-available', input: { html: '<html>…' } }))
      .toEqual({ text: 'Saving the app…', state: 'busy' })
    expect(toolLine({ type: 'tool-save_draft', state: 'output-available', output: { saved: true, bytes: 6048 } }))
      .toEqual({ text: 'Saved the app (6 KB)', state: 'done' })
    expect(toolLine({ type: 'tool-save_draft', state: 'output-error', errorText: 'too large' }))
      .toEqual({ text: 'Could not save the app: too large', state: 'error' })
  })

  it('names the app get_app read', () => {
    expect(toolLine({ type: 'tool-get_app', state: 'output-available', input: { slug: 'lunch-poll' } }).text)
      .toBe('Read lunch-poll')
    expect(toolLine({ type: 'tool-get_app', state: 'output-error', input: { slug: 'x' }, errorText: 'There is no app x' }).text)
      .toBe('Could not read x: There is no app x')
  })

  it('has a plain line for tools it does not know', () => {
    expect(toolLine({ type: 'tool-list_spaces', state: 'output-available' }).text).toBe('list_spaces: done')
  })
})
