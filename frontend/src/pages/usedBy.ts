import type { DependencyView } from '../api/types'

export interface DataUser {
  slug: string
  name: string
  archived: boolean
  /** Collections of this app it read or wrote, sorted. */
  collections: string[]
}

/** The other apps that used `slug`'s data, most recently first; the app's own use is left out. */
export function otherUsers(rows: DependencyView[], slug: string): DataUser[] {
  const users = new Map<string, DataUser>()
  for (const row of rows) {
    if (row.app === slug) continue
    const user = users.get(row.app) ?? { slug: row.app, name: row.appName, archived: row.archived, collections: [] }
    if (!user.collections.includes(row.collection)) user.collections.push(row.collection)
    users.set(row.app, user)
  }
  return [...users.values()].map((user) => ({ ...user, collections: [...user.collections].sort() }))
}
