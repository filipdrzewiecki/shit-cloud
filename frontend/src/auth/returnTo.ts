/**
 * Where to go after signing in. Only same-app paths are accepted: `returnTo`
 * arrives in the query string, and following an arbitrary value would make the
 * sign-in page an open redirect.
 */
export function safeReturnTo(value: string | null): string {
  if (!value || !value.startsWith('/') || value.startsWith('//') || value.startsWith('/sign-in')) return '/'
  return value
}

export function signInPath(returnTo: string): string {
  return `/sign-in?returnTo=${encodeURIComponent(returnTo)}`
}
