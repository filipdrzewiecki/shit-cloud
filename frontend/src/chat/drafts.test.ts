import type { UIMessage } from 'ai'
import { describe, expect, it } from 'vitest'
import { draftOf, lastDraft } from './drafts'

const message = (id: string, ...parts: unknown[]) => ({ id, role: 'assistant', parts }) as UIMessage

describe('drafts', () => {
  it('reads the html and the app being changed from a data-draft part', () => {
    expect(draftOf({ type: 'data-draft', data: { html: '<html>', targetSlug: 'lunch-poll' } }))
      .toEqual({ html: '<html>', targetSlug: 'lunch-poll' })
    expect(draftOf({ type: 'data-draft', data: { html: '<html>', targetSlug: null } }))
      .toEqual({ html: '<html>', targetSlug: null })
    expect(draftOf({ type: 'data-conversation', data: { id: 'c' } })).toBeNull()
    expect(draftOf({ type: 'data-draft', data: { html: 42 } })).toBeNull()
  })

  it('finds the newest draft of a conversation', () => {
    const messages = [
      message('m1', { type: 'data-draft', data: { html: 'v1' } }),
      message('m2', { type: 'text', text: 'no draft here' }),
      message('m3', { type: 'data-draft', data: { html: 'v2' } }, { type: 'data-draft', data: { html: 'v3' } }),
      message('m4', { type: 'text', text: 'Done' }),
    ]
    expect(lastDraft(messages)?.html).toBe('v3')
    expect(lastDraft([message('m', { type: 'text', text: 'x' })])).toBeNull()
  })
})
