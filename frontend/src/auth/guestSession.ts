import { useSyncExternalStore } from 'react'

/**
 * The guest token from the shared-password sign-in, kept in localStorage so a
 * reload does not ask for the password again. It lives on the platform origin,
 * which is one more reason user apps must never run there (see AppFrame).
 */
export interface GuestSession {
  token: string
  expiresAt: string
}

const KEY = 'shitcloud.guest'
const listeners = new Set<() => void>()

/** The cached value keeps useSyncExternalStore from seeing a new object on every read. */
let cached: { raw: string | null; session: GuestSession | null } = { raw: null, session: null }

function read(): string | null {
  try {
    return localStorage.getItem(KEY)
  } catch {
    return null
  }
}

/** Null once the token has expired; the API would reject it anyway. */
export function currentGuest(): GuestSession | null {
  const raw = read()
  if (raw !== cached.raw) {
    let session: GuestSession | null = null
    try {
      session = raw ? (JSON.parse(raw) as GuestSession) : null
    } catch {
      session = null
    }
    cached = { raw, session }
  }
  const session = cached.session
  return session && Date.parse(session.expiresAt) > Date.now() ? session : null
}

export function saveGuest(session: GuestSession): void {
  try {
    localStorage.setItem(KEY, JSON.stringify(session))
  } catch {
    // Storage blocked: the sign-in cannot stick, and the gate will ask again.
  }
  listeners.forEach((listener) => listener())
}

export function clearGuest(): void {
  try {
    localStorage.removeItem(KEY)
  } catch {
    // Nothing stored, nothing to clear.
  }
  cached = { raw: null, session: null }
  listeners.forEach((listener) => listener())
}

function subscribe(listener: () => void) {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

export function useGuestSession(): GuestSession | null {
  return useSyncExternalStore(subscribe, currentGuest)
}
