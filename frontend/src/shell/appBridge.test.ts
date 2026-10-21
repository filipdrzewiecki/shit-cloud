import { describe, expect, it, vi } from 'vitest'
import { ApiError, UnauthenticatedError } from '../api/client'
import type { DocView } from '../api/types'
import { acceptRequest, type BridgeBackend, type BridgeRequest, handleRequest } from './appBridge'

const APP_ORIGIN = 'https://apps.example'
const frame = { name: 'the app frame' }
const request: BridgeRequest = { shitcloud: 1, id: 7, op: 'find', collection: 'days' }

const doc: DocView = {
  id: 'a',
  data: { n: 1 },
  version: 1,
  createdBy: 'guest:Anna',
  createdAt: '2026-10-19T10:00:00Z',
  updatedBy: 'guest:Anna',
  updatedAt: '2026-10-19T10:00:00Z',
}

function backend(overrides: Partial<BridgeBackend> = {}): BridgeBackend {
  return {
    me: vi.fn(async () => ({ id: 'guest:Anna', displayName: 'Anna', groups: [], builder: false, admin: false, guest: true })),
    query: vi.fn(async () => [doc]),
    set: vi.fn(async () => doc),
    add: vi.fn(async () => doc),
    remove: vi.fn(async () => ({ deleted: true })),
    ...overrides,
  }
}

describe('acceptRequest', () => {
  it('accepts a request from the app frame on the app origin', () => {
    expect(acceptRequest({ source: frame, origin: APP_ORIGIN, data: request }, frame, APP_ORIGIN)).toEqual(request)
  })

  it('ignores another window, even on the same origin', () => {
    // On a shared apps origin another app has the same origin: only the window tells them apart.
    expect(acceptRequest({ source: {}, origin: APP_ORIGIN, data: request }, frame, APP_ORIGIN)).toBeNull()
  })

  it('ignores the app frame when its origin is not the app origin', () => {
    expect(acceptRequest({ source: frame, origin: 'https://evil.example', data: request }, frame, APP_ORIGIN)).toBeNull()
  })

  it('ignores everything when there is no frame', () => {
    expect(acceptRequest({ source: null, origin: APP_ORIGIN, data: request }, null, APP_ORIGIN)).toBeNull()
  })

  it.each([null, 'text', {}, { shitcloud: 2, id: 1, op: 'find' }, { shitcloud: 1, op: 'find' }, { shitcloud: 1, id: 1 }])(
    'ignores messages that are not requests: %j',
    (data) => {
      expect(acceptRequest({ source: frame, origin: APP_ORIGIN, data }, frame, APP_ORIGIN)).toBeNull()
    },
  )
})

describe('handleRequest', () => {
  it('finds in the space of the app on screen by default', async () => {
    const api = backend()

    await handleRequest(request, 'kalendarz', api)

    expect(api.query).toHaveBeenCalledWith('kalendarz', 'days', { filter: undefined, sort: undefined, limit: undefined })
  })

  it('lets find read another app, named in the message (db.space(slug).find)', async () => {
    const api = backend()

    await handleRequest({ ...request, space: 'team-calendar' }, 'kalendarz', api)

    expect(api.query).toHaveBeenCalledWith('team-calendar', 'days', { filter: undefined, sort: undefined, limit: undefined })
  })

  it('writes only to the space of the app on screen, whatever the message says', async () => {
    const api = backend()
    const other = { space: 'team-calendar', collection: 'days' }

    await handleRequest({ ...request, ...other, op: 'set', docId: 'a', data: { n: 1 } }, 'kalendarz', api)
    await handleRequest({ ...request, ...other, op: 'add', data: { n: 1 } }, 'kalendarz', api)
    await handleRequest({ ...request, ...other, op: 'remove', docId: 'a' }, 'kalendarz', api)

    expect(api.set).toHaveBeenCalledWith('kalendarz', 'days', 'a', { n: 1 }, undefined)
    expect(api.add).toHaveBeenCalledWith('kalendarz', 'days', { n: 1 })
    expect(api.remove).toHaveBeenCalledWith('kalendarz', 'days', 'a', undefined)
  })

  it.each([123, '', {}])('refuses a space that is not a slug text: %j', async (space) => {
    const api = backend()

    const reply = await handleRequest({ ...request, space }, 'kalendarz', api)

    expect(reply).toMatchObject({ ok: false, error: { code: 'BAD_REQUEST', message: 'space must be text' } })
    expect(api.query).not.toHaveBeenCalled()
  })

  it('answers find with the documents', async () => {
    const reply = await handleRequest(
      { ...request, filter: { n: { $gt: 0 } }, sort: { n: -1 }, limit: 5 },
      'kalendarz',
      backend(),
    )

    expect(reply).toEqual({ shitcloud: 1, id: 7, ok: true, result: [doc] })
  })

  it('passes set, add and remove on', async () => {
    const api = backend()

    await handleRequest({ shitcloud: 1, id: 1, op: 'set', collection: 'days', docId: 'a', data: { n: 2 }, expectedVersion: 1 }, 'k', api)
    await handleRequest({ shitcloud: 1, id: 2, op: 'add', collection: 'votes', data: { p: 'x' } }, 'k', api)
    const removed = await handleRequest({ shitcloud: 1, id: 3, op: 'remove', collection: 'days', docId: 'a' }, 'k', api)

    expect(api.set).toHaveBeenCalledWith('k', 'days', 'a', { n: 2 }, 1)
    expect(api.add).toHaveBeenCalledWith('k', 'votes', { p: 'x' })
    expect(api.remove).toHaveBeenCalledWith('k', 'days', 'a', undefined)
    expect(removed.result).toBe(true)
  })

  it('answers me with the person, without their groups', async () => {
    const reply = await handleRequest({ shitcloud: 1, id: 1, op: 'me' }, 'k', backend())

    expect(reply.result).toEqual({ id: 'guest:Anna', displayName: 'Anna', guest: true })
  })

  it.each([
    { op: 'drop' },
    { op: 'find' },
    { op: 'find', collection: 'days', filter: 'x' },
    { op: 'find', collection: 'days', limit: '5' },
    { op: 'set', collection: 'days', docId: 'a', data: [1] },
    { op: 'set', collection: 'days', data: {} },
    { op: 'add', collection: 'days' },
  ])('refuses malformed requests: %j', async (args) => {
    const api = backend()

    const reply = await handleRequest({ shitcloud: 1, id: 1, ...args } as BridgeRequest, 'k', api)

    expect(reply.ok).toBe(false)
    expect(reply.error?.code).toBe('BAD_REQUEST')
    expect(api.query).not.toHaveBeenCalled()
    expect(api.set).not.toHaveBeenCalled()
  })

  it('passes the Data API error code on', async () => {
    const api = backend({ set: vi.fn(async () => Promise.reject(new ApiError(409, 'Changed', 'VERSION_CONFLICT'))) })

    const reply = await handleRequest({ shitcloud: 1, id: 1, op: 'set', collection: 'd', docId: 'a', data: {} }, 'k', api)

    expect(reply.error).toEqual({ code: 'VERSION_CONFLICT', message: 'Changed' })
  })

  it('reports a lost session and an unreachable API', async () => {
    const signedOut = await handleRequest(request, 'k', backend({ query: vi.fn(async () => Promise.reject(new UnauthenticatedError())) }))
    const offline = await handleRequest(request, 'k', backend({ query: vi.fn(async () => Promise.reject(new TypeError('fetch failed'))) }))

    expect(signedOut.error?.code).toBe('SIGNED_OUT')
    expect(offline.error?.code).toBe('UNAVAILABLE')
  })
})
