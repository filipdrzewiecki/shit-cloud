/**
 * Where to go after signing in. Only same-app paths are accepted: `returnTo`
 * arrives in the query string, and following an arbitrary value would make the
 * sign-in page an open redirect.
 */
export function safeReturnTo(value: string | null): string {
  if (!value || !value.startsWith('/') || value.startsWith('//') || value.startsWith('/sign-in')) return '/'
  return value
}

/** Why the reader landed on the sign-in page, when it was not simply a missing session. */
export type SignInReason = 'builder-rejected'

export function signInPath(returnTo: string, reason?: SignInReason): string {
  const params = new URLSearchParams({ returnTo })
  if (reason) params.set('reason', reason)
  return `/sign-in?${params.toString()}`
}
