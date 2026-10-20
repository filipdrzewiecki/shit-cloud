import { useEffect, useRef, useState, type FormEvent } from 'react'
import { useChat } from '@ai-sdk/react'
import type { UIMessage } from 'ai'
import { draftOf, type Draft } from './drafts'
import { MessageView } from './parts'
import { serverTransport, type ChatFetch } from './serverChat'
import './chat.css'

export interface ChatPanelProps {
  /** The chat endpoint, e.g. `/api/chat`; `fetch` resolves it against the server. */
  endpoint: string
  fetch: ChatFetch
  /** Continue a saved conversation: its id and messages. */
  conversationId?: string | null
  initialMessages?: UIMessage[]
  /** Change an existing app: sent when the conversation starts. */
  targetSlug?: string | null
  greeting?: string
  placeholder?: string
  /** A new draft arrived: show it in the preview. */
  onDraft?: (draft: Draft) => void
  /** The server named the new conversation (first answer only). */
  onConversation?: (conversation: { id: string; title: string }) => void
}

/** Seconds without a new part after which the answer counts as quiet (the model is writing a long tool input). */
const QUIET_AFTER_SECONDS = 3

/**
 * A chat with the server: streamed answers, tool steps as short lines, Stop. Remount it (a new `key`) to switch
 * conversations — `useChat` keeps one conversation per instance.
 */
export function ChatPanel(props: ChatPanelProps) {
  const { endpoint, fetch, conversationId = null, initialMessages, targetSlug = null } = props
  const conversation = useRef<string | null>(conversationId)
  // The SDK keeps the callbacks it was created with; these refs always point at the latest props.
  const onDraft = useRef(props.onDraft)
  const onConversation = useRef(props.onConversation)
  onDraft.current = props.onDraft
  onConversation.current = props.onConversation

  const [transport] = useState(() =>
    serverTransport({ endpoint, fetch, conversationId: () => conversation.current, targetSlug }),
  )
  const chat = useChat({
    id: conversationId ?? undefined,
    messages: initialMessages,
    transport,
    throttle: 50,
    onData: (part) => {
      if (part.type === 'data-conversation') {
        const data = part.data as { id: string; title: string }
        conversation.current = data.id
        onConversation.current?.(data)
        return
      }
      const draft = draftOf(part)
      if (draft) onDraft.current?.(draft)
    },
  })

  const busy = chat.status === 'submitted' || chat.status === 'streaming'
  const quietFor = useQuietSeconds(busy, chat.messages)
  const [input, setInput] = useState('')
  const list = useRef<HTMLDivElement>(null)
  const followBottom = useRef(true)

  useEffect(() => {
    const element = list.current
    if (element && followBottom.current) element.scrollTop = element.scrollHeight
  }, [chat.messages, busy, chat.error])

  const send = (e?: FormEvent) => {
    e?.preventDefault()
    const text = input.trim()
    if (!text || busy) return
    setInput('')
    followBottom.current = true
    void chat.sendMessage({ text })
  }

  return (
    <div className="sc-chat">
      <div
        className="sc-chat-messages"
        ref={list}
        onScroll={(e) => {
          const element = e.currentTarget
          followBottom.current = element.scrollHeight - element.scrollTop - element.clientHeight < 40
        }}
      >
        {chat.messages.length === 0 && props.greeting && (
          <div className="sc-chat-message sc-chat-assistant">{props.greeting}</div>
        )}
        {chat.messages.map((message) => (
          <MessageView key={message.id} message={message} />
        ))}
        {busy && (
          <div className="sc-chat-working">
            {chat.status === 'submitted' ? 'Thinking…' : quietFor >= QUIET_AFTER_SECONDS ? `Working… ${quietFor} s` : 'Writing…'}
          </div>
        )}
        {chat.error && (
          <div className="sc-chat-error">
            <span>{chat.error.message}</span>
            <button type="button" onClick={() => chat.clearError()}>
              Dismiss
            </button>
          </div>
        )}
      </div>
      <form className="sc-chat-composer" onSubmit={send}>
        <textarea
          rows={3}
          placeholder={props.placeholder}
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && !e.shiftKey) send(e)
          }}
        />
        {busy ? (
          <button type="button" onClick={() => void chat.stop()}>
            Stop
          </button>
        ) : (
          <button className="primary" disabled={!input.trim()}>
            Send
          </button>
        )}
      </form>
    </div>
  )
}

/**
 * Seconds since the last streamed change while an answer runs; 0 otherwise. Keeps a quiet stream from looking
 * frozen — the model can spend a minute writing an app without sending a single part.
 */
function useQuietSeconds(busy: boolean, messages: UIMessage[]): number {
  const lastChange = useRef(Date.now())
  const [now, setNow] = useState(Date.now())

  useEffect(() => {
    lastChange.current = Date.now()
  }, [messages, busy])

  useEffect(() => {
    if (!busy) return
    const timer = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(timer)
  }, [busy])

  return busy ? Math.max(0, Math.floor((now - lastChange.current) / 1000)) : 0
}
