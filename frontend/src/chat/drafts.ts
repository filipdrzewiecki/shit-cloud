import type { UIMessage } from 'ai'

/** A whole app the chat produced, for the preview. `targetSlug` is the app it changes, null for a new one. */
export interface Draft {
  html: string
  targetSlug: string | null
}

/** The draft in a `data-draft` part, or null for any other part. */
export function draftOf(part: { type: string; data?: unknown }): Draft | null {
  if (part.type !== 'data-draft' || typeof part.data !== 'object' || part.data === null) return null
  const data = part.data as { html?: unknown; targetSlug?: unknown }
  if (typeof data.html !== 'string') return null
  return { html: data.html, targetSlug: typeof data.targetSlug === 'string' ? data.targetSlug : null }
}

/** The newest draft in a conversation — what its preview shows when it is opened again. */
export function lastDraft(messages: UIMessage[]): Draft | null {
  for (let m = messages.length - 1; m >= 0; m--) {
    const parts = messages[m].parts
    for (let p = parts.length - 1; p >= 0; p--) {
      const draft = draftOf(parts[p] as { type: string; data?: unknown })
      if (draft) return draft
    }
  }
  return null
}
