import { afterEach, describe, expect, it, vi } from 'vitest'
// The SDK is served by the API (injected into every app); it is tested here because this is where a JS test runner is.
import sdkSource from '../../../src/main/resources/apps/sdk.js?raw'

interface Sent {
  message: Record<string, unknown>
  target: string
}

/** Runs the SDK in a hand-made window, framed (with a parent page) or opened in a tab of its own. */
function loadSdk({ framed = true, cloneFails = false } = {}) {
  const listeners: ((event: { data: unknown; source: unknown }) => void)[] = []
  const sent: Sent[] = []
  const win: Record<string, unknown> = {
    addEventListener: (_type: string, listener: (event: { data: unknown; source: unknown }) => void) => listeners.push(listener),
  }
  const parent = {
    postMessage: (message: Record<string, unknown>, target: string) => {
      if (cloneFails) throw new Error('DataCloneError')
      sent.push({ message, target })
    },
  }
  win.parent = framed ? parent : win
  new Function('window', sdkSource)(win)

  const deliver = (data: unknown, source: unknown = parent) => listeners.forEach((listener) => listener({ data, source }))
  return { shitcloud: win.shitcloud as any, sent, deliver, win }
}

const doc = { id: 'a', data: { n: 1 }, version: 2, updatedBy: 'guest:Anna', updatedAt: '2026-10-19T10:00:00Z' }

afterEach(() => {
  vi.useRealTimers()
})

describe('SDK', () => {
  it('sends a request to the parent and resolves with the matching reply', async () => {
    const { shitcloud, sent, deliver } = loadSdk()

    const found = shitcloud.db.find('days', { n: 1 }, { sort: { n: -1 }, limit: 5 })
    const request = sent[0].message
    deliver({ shitcloud: 1, id: request.id, ok: true, result: [doc] })

    expect(request).toMatchObject({ shitcloud: 1, op: 'find', collection: 'days', filter: { n: 1 }, sort: { n: -1 }, limit: 5 })
    expect(sent[0].target).toBe('*')
    // Flat for the app: its fields plus id and _ metadata.
    expect(await found).toEqual([{ n: 1, id: 'a', _version: 2, _updatedBy: 'guest:Anna', _updatedAt: '2026-10-19T10:00:00Z' }])
  })

  it('matches replies to requests by id', async () => {
    const { shitcloud, sent, deliver } = loadSdk()

    const first = shitcloud.db.add('votes', { p: 1 })
    const second = shitcloud.db.add('votes', { p: 2 })
    deliver({ shitcloud: 1, id: sent[1].message.id, ok: true, result: { ...doc, id: 'second' } })
    deliver({ shitcloud: 1, id: sent[0].message.id, ok: true, result: { ...doc, id: 'first' } })

    expect(await first).toBe('first')
    expect(await second).toBe('second')
  })

  it('ignores replies from any window but the parent', async () => {
    vi.useFakeTimers()
    const { shitcloud, sent, deliver } = loadSdk()

    const removed = shitcloud.db.remove('days', 'a')
    deliver({ shitcloud: 1, id: sent[0].message.id, ok: true, result: true }, { other: 'window' })
    vi.advanceTimersByTime(15_000)

    await expect(removed).rejects.toMatchObject({ code: 'TIMEOUT' })
  })

  it('rejects with the code from the reply', async () => {
    const { shitcloud, sent, deliver } = loadSdk()

    const write = shitcloud.db.set('days', 'a', { n: 1 }, { expectedVersion: 1 })
    deliver({ shitcloud: 1, id: sent[0].message.id, ok: false, error: { code: 'VERSION_CONFLICT', message: 'Changed' } })

    expect(sent[0].message).toMatchObject({ op: 'set', docId: 'a', data: { n: 1 }, expectedVersion: 1 })
    await expect(write).rejects.toMatchObject({ code: 'VERSION_CONFLICT', message: 'Changed', name: 'ShitCloudError' })
  })

  it('in a tab of its own, rejects every call without sending anything', async () => {
    const { shitcloud, sent } = loadSdk({ framed: false })

    await expect(shitcloud.me()).rejects.toMatchObject({ code: 'NOT_IN_SHITCLOUD' })
    await expect(shitcloud.db.find('days')).rejects.toMatchObject({ code: 'NOT_IN_SHITCLOUD' })
    expect(sent).toHaveLength(0)
  })

  it('refuses data that cannot be sent', async () => {
    const { shitcloud } = loadSdk({ cloneFails: true })

    await expect(shitcloud.db.add('votes', { fn: () => 1 })).rejects.toMatchObject({ code: 'BAD_REQUEST' })
  })

  it('cannot be replaced or changed by the app', () => {
    const { shitcloud, win } = loadSdk()

    expect(Object.isFrozen(shitcloud)).toBe(true)
    expect(Object.isFrozen(shitcloud.db)).toBe(true)
    // Test modules are strict code, where writing a read-only property throws.
    expect(() => {
      win.shitcloud = {}
    }).toThrow()
    expect(shitcloud.version).toBe(3)
  })

  it('reads another app with space(slug).find, naming that space in the request', async () => {
    const { shitcloud, sent, deliver } = loadSdk()

    const found = shitcloud.db.space('team-calendar').find('marks', { n: 1 }, { limit: 2 })
    deliver({ shitcloud: 1, id: sent[0].message.id, ok: true, result: [doc] })

    expect(sent[0].message).toMatchObject({ op: 'find', space: 'team-calendar', collection: 'marks', filter: { n: 1 }, limit: 2 })
    expect(await found).toEqual([{ n: 1, id: 'a', _version: 2, _updatedBy: 'guest:Anna', _updatedAt: '2026-10-19T10:00:00Z' }])
  })

  it('offers nothing but find on another app, frozen', () => {
    const { shitcloud } = loadSdk()

    const other = shitcloud.db.space('team-calendar')

    expect(Object.keys(other)).toEqual(['find'])
    expect(Object.isFrozen(other)).toBe(true)
  })

  it('own find names no space, so the host uses the app on screen', () => {
    const { shitcloud, sent } = loadSdk()

    void shitcloud.db.find('days')

    expect(sent[0].message.space).toBeUndefined()
  })

  it.each([undefined, '', 42])('rejects space() without a slug: %j', async (slug) => {
    const { shitcloud, sent } = loadSdk()

    await expect(shitcloud.db.space(slug).find('marks')).rejects.toMatchObject({ code: 'BAD_REQUEST' })
    expect(sent).toHaveLength(0)
  })

  it('leaves an existing window.shitcloud alone', () => {
    const existing = { mine: true }
    const win: Record<string, unknown> = { shitcloud: existing, addEventListener: () => {} }
    win.parent = win

    new Function('window', sdkSource)(win)

    expect(win.shitcloud).toBe(existing)
  })
})
