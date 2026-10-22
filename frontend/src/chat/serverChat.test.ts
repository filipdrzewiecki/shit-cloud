import type { UIMessage } from 'ai'
import { describe, expect, it } from 'vitest'
import { requestBody } from './serverChat'

const history = [
  { id: 'u1', role: 'user', parts: [{ type: 'text', text: 'Make a poll' }] },
  { id: 'm1', role: 'assistant', parts: [{ type: 'text', text: 'Done' }] },
  { id: 'u2', role: 'user', parts: [{ type: 'text', text: 'Add a deadline' }, { type: 'file', url: 'x' }] },
] as UIMessage[]

describe('requestBody', () => {
  it('sends only the new message, as text', () => {
    expect(requestBody(history, 'c_1', null)).toEqual({
      conversationId: 'c_1',
      message: { id: 'u2', role: 'user', parts: [{ type: 'text', text: 'Add a deadline' }] },
      targetSlug: null,
    })
  })

  it('names the app to change only when the conversation starts', () => {
    expect(requestBody(history.slice(0, 1), null, 'lunch-poll').targetSlug).toBe('lunch-poll')
    expect(requestBody(history, 'c_1', 'lunch-poll').targetSlug).toBeNull()
  })

  it('adds the host\'s start fields (an uploaded file) only when the conversation starts', () => {
    const upload = { upload: { fileName: 'a.html', html: '<html></html>' } }
    expect(requestBody(history.slice(0, 1), null, null, upload)).toMatchObject(upload)
    expect(requestBody(history, 'c_1', null, upload)).not.toHaveProperty('upload')
  })
})
