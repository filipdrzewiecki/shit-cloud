import type { UIMessage } from 'ai'
import type { MigrationCounts } from '../api/types'

/** How many documents gain, lose or change a field. */
export interface FieldChange {
  added: number
  removed: number
  changed: number
}

/** A data change the chat checked (`data-migration`); nothing is written until the HTML is replaced with it. */
export interface MigrationProposal {
  id: number
  space: string
  collections: string[]
  summary: string | null
  counts: MigrationCounts
  byCollection: Record<string, MigrationCounts>
  /** Per collection, the top-level fields that change. */
  fields: Record<string, Record<string, FieldChange>>
  /** `after` is null for a removed document. */
  examples: { collection: string; id: string; before: unknown; after: unknown | null }[]
  failures: { collection: string; id: string; error: string }[]
  /** Other apps that read the converted collections. */
  readers: { app: string; name: string; collections: string[] }[]
}

/** The proposal in a `data-migration` part, or null for any other part. */
export function migrationOf(part: { type: string; data?: unknown }): MigrationProposal | null {
  if (part.type !== 'data-migration' || typeof part.data !== 'object' || part.data === null) return null
  const data = part.data as Partial<MigrationProposal>
  if (typeof data.id !== 'number' || typeof data.space !== 'string' || typeof data.counts !== 'object') return null
  return {
    id: data.id,
    space: data.space,
    collections: data.collections ?? [],
    summary: data.summary ?? null,
    counts: data.counts as MigrationCounts,
    byCollection: data.byCollection ?? {},
    fields: data.fields ?? {},
    examples: data.examples ?? [],
    failures: data.failures ?? [],
    readers: data.readers ?? [],
  }
}

/** The newest proposal in a conversation: the one Replace HTML would apply. */
export function lastMigration(messages: UIMessage[]): MigrationProposal | null {
  for (let m = messages.length - 1; m >= 0; m--) {
    const parts = messages[m].parts
    for (let p = parts.length - 1; p >= 0; p--) {
      const migration = migrationOf(parts[p] as { type: string; data?: unknown })
      if (migration) return migration
    }
  }
  return null
}

/** "team: added to 12; kind: removed from 12" — one line per collection. */
export function fieldLines(fields: MigrationProposal['fields']): string[] {
  return Object.entries(fields).map(([collection, byField]) => {
    const changes = Object.entries(byField).flatMap(([field, change]) => [
      ...(change.added > 0 ? [`${field} added to ${change.added}`] : []),
      ...(change.removed > 0 ? [`${field} removed from ${change.removed}`] : []),
      ...(change.changed > 0 ? [`${field} changed in ${change.changed}`] : []),
    ])
    return `${collection}: ${changes.join('; ')}`
  })
}
