import { currentGuest } from '../auth/guestSession'

/**
 * The API is a separate service on its own origin, so every path in `api/` is
 * relative and gets the origin put in front of it here. This is the only place
 * an absolute URL is assembled, and the only place a bearer token is attached.
 */
const API_BASE_URL: string = import.meta.env.VITE_API_BASE_URL ?? ''

export class ApiError extends Error {
  readonly status: number

  constructor(status: number, message: string) {
    super(message)
    this.status = status
  }
}

/** The session is gone or was never there; the registered handler sends the reader to sign in. */
export class UnauthenticatedError extends ApiError {
  constructor() {
    super(401, 'Signed out')
  }
}

type TokenProvider = () => Promise<string | undefined>

/**
 * The Auth0 SDK hands out access tokens through a React hook, so its getter is
 * supplied from the component tree rather than imported. Null when the reader
 * did not sign in through Auth0; the guest token is used then.
 */
let idpTokenProvider: TokenProvider | null = null
let unauthenticatedHandler: (() => void) | null = null

export function setIdpTokenProvider(provider: TokenProvider | null): void {
  idpTokenProvider = provider
}

export function setUnauthenticatedHandler(handler: () => void): void {
  unauthenticatedHandler = handler
}

function unauthenticated(): UnauthenticatedError {
  unauthenticatedHandler?.()
  return new UnauthenticatedError()
}

async function bearer(): Promise<string> {
  if (idpTokenProvider) {
    let token: string | undefined
    try {
      token = await idpTokenProvider()
    } catch {
      // The SDK refuses to mint a token when the refresh token expired or was revoked.
      throw unauthenticated()
    }
    if (token) return token
    throw unauthenticated()
  }
  const guest = currentGuest()
  if (guest) return guest.token
  throw unauthenticated()
}

type Method = 'GET' | 'POST' | 'PUT' | 'DELETE'

interface RequestOptions {
  /** Sent without a token, and a 401 is the endpoint's answer rather than a lost session. */
  anonymous?: boolean
}

async function request<T>(method: Method, path: string, body?: unknown, options: RequestOptions = {}): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' }
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  if (!options.anonymous) headers.Authorization = `Bearer ${await bearer()}`

  const response = await fetch(API_BASE_URL + path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  })

  if (response.status === 401 && !options.anonymous) throw unauthenticated()

  if (!response.ok) {
    // Spring's default error body carries a short `error`; the reason passed to
    // ResponseStatusException is not exposed by default, so the status is the
    // most reliable thing to show.
    let detail = response.statusText
    try {
      const json = (await response.json()) as { error?: string }
      if (json.error) detail = json.error
    } catch {
      // Not JSON — keep the status text.
    }
    throw new ApiError(response.status, `${response.status} ${detail}`)
  }

  return (await response.json()) as T
}

export const api = {
  get: <T>(path: string) => request<T>('GET', path),
  post: <T>(path: string, body: unknown, options?: RequestOptions) => request<T>('POST', path, body, options),
}
