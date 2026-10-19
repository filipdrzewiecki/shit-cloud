import { api } from './client'
import type { DataQuery, DocView } from './types'

/**
 * The Data API, called only by the app bridge on behalf of the app on screen.
 * `space` is always the slug of that app, taken from the route.
 */
function base(space: string, collection: string): string {
  return `/api/data/${encodeURIComponent(space)}/${encodeURIComponent(collection)}`
}

export const dataApi = {
  query: (space: string, collection: string, query: DataQuery) =>
    api.post<DocView[]>(`${base(space, collection)}/query`, query),

  set: (space: string, collection: string, id: string, data: unknown, expectedVersion?: number) =>
    api.put<DocView>(`${base(space, collection)}/${encodeURIComponent(id)}`, { data, expectedVersion }),

  add: (space: string, collection: string, data: unknown) => api.post<DocView>(base(space, collection), { data }),

  remove: (space: string, collection: string, id: string, expectedVersion?: number) => {
    const version = expectedVersion === undefined ? '' : `?expectedVersion=${expectedVersion}`
    return api.delete<{ deleted: boolean }>(`${base(space, collection)}/${encodeURIComponent(id)}${version}`)
  },
}
