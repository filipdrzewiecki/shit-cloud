/**
 * The API is a separate service on its own origin, so every path in `api/` is
 * relative and gets the origin put in front of it here. This is the only place
 * an absolute URL is assembled.
 */
const API_BASE_URL: string = import.meta.env.VITE_API_BASE_URL ?? ''

export class ApiError extends Error {
  readonly status: number

  constructor(status: number, message: string) {
    super(message)
    this.status = status
  }
}

type Method = 'GET' | 'POST' | 'PUT' | 'DELETE'

async function request<T>(method: Method, path: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' }
  if (body !== undefined) headers['Content-Type'] = 'application/json'

  const response = await fetch(API_BASE_URL + path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  })

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
  post: <T>(path: string, body: unknown) => request<T>('POST', path, body),
}
