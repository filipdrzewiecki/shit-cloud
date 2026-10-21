import { api } from './client'
import type { DataQuery, DocView } from './types'

/**
 * The Data API, called only by the app bridge on behalf of the app on screen. `reader` is the slug of that app,
 * from the route; the server notes which app used which data under it (the dependency log). `space` is that same
 * app for writes, and for `query` also another app whose data it reads.
 */
function base(space: string, collection: string): string {
  return `/api/data/${encodeURIComponent(space)}/${encodeURIComponent(collection)}`
}

function as(reader: string) {
  return { headers: { 'X-ShitCloud-App': reader } }
}

export const dataApi = {
  query: (space: string, collection: string, query: DataQuery, reader: string) =>
    api.post<DocView[]>(`${base(space, collection)}/query`, query, as(reader)),

  set: (space: string, collection: string, id: string, data: unknown, expectedVersion: number | undefined, reader: string) =>
    api.put<DocView>(`${base(space, collection)}/${encodeURIComponent(id)}`, { data, expectedVersion }, as(reader)),

  add: (space: string, collection: string, data: unknown, reader: string) =>
    api.post<DocView>(base(space, collection), { data }, as(reader)),

  remove: (space: string, collection: string, id: string, expectedVersion: number | undefined, reader: string) => {
    const version = expectedVersion === undefined ? '' : `?expectedVersion=${expectedVersion}`
    return api.delete<{ deleted: boolean }>(`${base(space, collection)}/${encodeURIComponent(id)}${version}`, as(reader))
  },
}
