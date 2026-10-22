import { DefaultChatTransport, type UIMessage } from 'ai'

/** A `fetch` that knows the server: adds credentials, and throws a readable Error for a failed response. */
export type ChatFetch = (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>

/**
 * What one turn sends. The server keeps the conversation, so only the new message travels — `useChat` would send
 * the whole history otherwise. `targetSlug` and `startBody` (the host's own fields, e.g. an uploaded file) matter
 * only when a conversation starts; after that the server keeps what it started with.
 */
export function requestBody(
  messages: UIMessage[],
  conversationId: string | null,
  targetSlug: string | null,
  startBody: Record<string, unknown> = {},
) {
  const message = messages[messages.length - 1]
  return {
    ...(conversationId ? {} : startBody),
    conversationId,
    message: { id: message.id, role: message.role, parts: message.parts.filter((part) => part.type === 'text') },
    targetSlug: conversationId ? null : targetSlug,
  }
}

/**
 * The SDK's transport with our request shape and our `fetch`. The SDK reports a failed response as its raw body;
 * our `fetch` throws first, so the chat shows the server's message (and a lost session signs out).
 */
export function serverTransport(options: {
  endpoint: string
  fetch: ChatFetch
  conversationId: () => string | null
  targetSlug: string | null
  startBody?: Record<string, unknown>
}) {
  return new DefaultChatTransport<UIMessage>({
    api: options.endpoint,
    fetch: options.fetch as typeof fetch,
    prepareSendMessagesRequest: ({ messages }) => ({
      body: requestBody(messages, options.conversationId(), options.targetSlug, options.startBody),
    }),
  })
}
