import { ApiError, UnauthenticatedError } from '../api/client'
import type { DataQuery, DocView, Me } from '../api/types'

/**
 * The host side of the bridge between an app and the API. Apps are untrusted
 * code on another origin; this module decides which of their messages are
 * answered and turns them into Data API calls. Kept free of React so every
 * rule here is unit-tested.
 *
 * The rules that make it safe:
 * - only the app's own frame window, from the app's origin, is listened to;
 * - writes go to the space of the app on screen, never one from the message;
 *   only `find` may name another app's space (reading is open to every app);
 * - replies go to the app's origin only.
 */

export interface BridgeRequest {
  shitcloud: 1
  id: number | string
  op: string
  [arg: string]: unknown
}

export interface BridgeReply {
  shitcloud: 1
  id: number | string
  ok: boolean
  result?: unknown
  error?: { code: string; message: string }
}

/** What the bridge needs from the platform; the real one wraps `dataApi` and the `/api/me` cache. */
export interface BridgeBackend {
  me(): Promise<Me>
  query(space: string, collection: string, query: DataQuery): Promise<DocView[]>
  set(space: string, collection: string, id: string, data: unknown, expectedVersion?: number): Promise<DocView>
  add(space: string, collection: string, data: unknown): Promise<DocView>
  remove(space: string, collection: string, id: string, expectedVersion?: number): Promise<{ deleted: boolean }>
}

interface MessageLike {
  source: unknown
  origin: string
  data: unknown
}

class BridgeError extends Error {
  readonly code: string

  constructor(code: string, message: string) {
    super(message)
    this.code = code
  }
}

/** The request in `event`, or null when the message is not one this frame's app may send. */
export function acceptRequest(event: MessageLike, frame: unknown, appOrigin: string): BridgeRequest | null {
  // Both checks: on a shared apps origin every app has the same origin, so only
  // the window tells this app apart from one open in another tab.
  if (!frame || event.source !== frame || event.origin !== appOrigin) return null
  const data = event.data as Partial<BridgeRequest> | null
  if (!data || typeof data !== 'object' || data.shitcloud !== 1) return null
  if ((typeof data.id !== 'number' && typeof data.id !== 'string') || typeof data.op !== 'string') return null
  return data as BridgeRequest
}

export async function handleRequest(request: BridgeRequest, space: string, backend: BridgeBackend): Promise<BridgeReply> {
  try {
    const result = await perform(request, space, backend)
    return { shitcloud: 1, id: request.id, ok: true, result }
  } catch (e) {
    return { shitcloud: 1, id: request.id, ok: false, error: toError(e) }
  }
}

async function perform(request: BridgeRequest, space: string, backend: BridgeBackend): Promise<unknown> {
  switch (request.op) {
    case 'me': {
      const me = await backend.me()
      return { id: me.id, displayName: me.displayName, guest: me.guest }
    }
    case 'find':
      // Only find may name another app's space (shitcloud.db.space(slug).find): reading is open to every app,
      // writing stays in the app's own space, whatever a message says.
      return backend.query(optionalText(request, 'space') ?? space, text(request, 'collection'), {
        filter: optionalObject(request, 'filter') as Record<string, unknown> | undefined,
        sort: optionalObject(request, 'sort') as Record<string, number> | undefined,
        limit: optionalNumber(request, 'limit'),
      })
    case 'set':
      return backend.set(
        space,
        text(request, 'collection'),
        text(request, 'docId'),
        object(request, 'data'),
        optionalNumber(request, 'expectedVersion'),
      )
    case 'add':
      return backend.add(space, text(request, 'collection'), object(request, 'data'))
    case 'remove': {
      const result = await backend.remove(
        space,
        text(request, 'collection'),
        text(request, 'docId'),
        optionalNumber(request, 'expectedVersion'),
      )
      return result.deleted
    }
    default:
      throw new BridgeError('BAD_REQUEST', `Unknown operation ${request.op}`)
  }
}

function text(request: BridgeRequest, name: string): string {
  const value = request[name]
  if (typeof value !== 'string' || value === '') throw new BridgeError('BAD_REQUEST', `${name} must be text`)
  return value
}

function optionalText(request: BridgeRequest, name: string): string | undefined {
  const value = request[name]
  if (value === undefined || value === null) return undefined
  if (typeof value !== 'string' || value === '') throw new BridgeError('BAD_REQUEST', `${name} must be text`)
  return value
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function object(request: BridgeRequest, name: string): Record<string, unknown> {
  const value = request[name]
  if (!isObject(value)) throw new BridgeError('BAD_REQUEST', `${name} must be an object`)
  return value
}

function optionalObject(request: BridgeRequest, name: string): Record<string, unknown> | undefined {
  const value = request[name]
  if (value === undefined || value === null) return undefined
  if (!isObject(value)) throw new BridgeError('BAD_REQUEST', `${name} must be an object`)
  return value
}

function optionalNumber(request: BridgeRequest, name: string): number | undefined {
  const value = request[name]
  if (value === undefined || value === null) return undefined
  if (typeof value !== 'number' || !Number.isFinite(value)) throw new BridgeError('BAD_REQUEST', `${name} must be a number`)
  return value
}

function toError(e: unknown): { code: string; message: string } {
  if (e instanceof BridgeError) return { code: e.code, message: e.message }
  if (e instanceof UnauthenticatedError) return { code: 'SIGNED_OUT', message: 'You were signed out of ShitCloud' }
  if (e instanceof ApiError) return { code: e.code ?? codeForStatus(e.status), message: e.message }
  return { code: 'UNAVAILABLE', message: 'ShitCloud could not be reached, try again' }
}

function codeForStatus(status: number): string {
  if (status === 403) return 'FORBIDDEN'
  if (status === 404) return 'NO_SUCH_APP'
  if (status === 409) return 'CONFLICT'
  if (status === 413) return 'TOO_LARGE'
  if (status >= 400 && status < 500) return 'BAD_REQUEST'
  return 'UNAVAILABLE'
}
