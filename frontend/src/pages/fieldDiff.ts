/**
 * How a top-level field of an earlier state compares with the document now:
 * - `same`: both hold an equal value;
 * - `differs`: both have it, with different values;
 * - `removedSince`: the earlier state had it, the document no longer does;
 * - `addedSince`: the document has it now, the earlier state did not.
 */
export type FieldState = 'same' | 'differs' | 'removedSince' | 'addedSince'

export interface FieldRow {
  field: string
  /** The earlier state's value; undefined for `addedSince`. */
  value: unknown
  /** The value now; undefined for `removedSince` (or when the document is removed). */
  now: unknown
  state: FieldState
}

/** Order-independent equality of JSON values. */
function sameJson(a: unknown, b: unknown): boolean {
  if (a === b) return true
  if (typeof a !== 'object' || typeof b !== 'object' || a === null || b === null) return false
  if (Array.isArray(a) !== Array.isArray(b)) return false
  if (Array.isArray(a) && Array.isArray(b)) return a.length === b.length && a.every((item, i) => sameJson(item, b[i]))
  const left = a as Record<string, unknown>
  const right = b as Record<string, unknown>
  const keys = Object.keys(left)
  return keys.length === Object.keys(right).length && keys.every((key) => key in right && sameJson(left[key], right[key]))
}

/**
 * The fields of an earlier state, each compared with the document now, followed by fields only the document now
 * has. With no current document (it was removed) every field of the earlier state counts as `removedSince`.
 */
export function fieldDiff(version: Record<string, unknown>, current: Record<string, unknown> | null): FieldRow[] {
  const now = current ?? {}
  const rows: FieldRow[] = Object.entries(version).map(([field, value]) => {
    if (!(field in now)) return { field, value, now: undefined, state: 'removedSince' }
    return { field, value, now: now[field], state: sameJson(value, now[field]) ? 'same' : 'differs' }
  })
  for (const [field, value] of Object.entries(now)) {
    if (!(field in version)) rows.push({ field, value: undefined, now: value, state: 'addedSince' })
  }
  return rows
}
