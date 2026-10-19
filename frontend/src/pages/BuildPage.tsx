import { useEffect, useRef, useState, type FormEvent } from 'react'
import { useChat } from '../api/queries'
import type { ChatMessage } from '../api/types'
import { DraftFrame } from '../shell/DraftFrame'
import { DeployBar } from './DeployBar'

const GREETING: ChatMessage = {
  role: 'assistant',
  content: 'Hi! Describe the app you need — e.g. "a team calendar where everyone marks office days and days off".',
}

/**
 * Conversation on the left, live preview on the right, Deploy outside the chat.
 * Will become the reusable chat package; for now it posts to the mock /api/chat.
 */
export function BuildPage() {
  const [messages, setMessages] = useState<ChatMessage[]>([GREETING])
  const [input, setInput] = useState('')
  const [draft, setDraft] = useState<string | null>(null)
  const chat = useChat()
  const bottom = useRef<HTMLDivElement>(null)

  useEffect(() => {
    bottom.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages, chat.isPending])

  const send = (e: FormEvent) => {
    e.preventDefault()
    const text = input.trim()
    if (!text || chat.isPending) return
    const next: ChatMessage[] = [...messages, { role: 'user', content: text }]
    setMessages(next)
    setInput('')
    chat.mutate(
      next.filter((m) => m !== GREETING),
      {
        onSuccess: (response) => {
          setMessages((current) => [...current, response.reply])
          if (response.draftHtml) setDraft(response.draftHtml)
        },
      },
    )
  }

  return (
    <section className="build">
      <div className="chat">
        <div className="messages">
          {messages.map((m, i) => (
            <div key={i} className={`message ${m.role}`}>
              {m.content}
            </div>
          ))}
          {chat.isPending && <div className="message assistant muted">Thinking…</div>}
          {chat.isError && <div className="message error-text">{chat.error.message}</div>}
          <div ref={bottom} />
        </div>
        <form className="composer" onSubmit={send}>
          <textarea
            rows={3}
            placeholder="Describe the app or ask for a change…"
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && !e.shiftKey) send(e)
            }}
          />
          <button className="primary" disabled={!input.trim() || chat.isPending}>
            Send
          </button>
        </form>
      </div>

      <div className="preview">
        <div className="preview-head">
          <strong>Preview</strong>
          <DeployBar html={draft} />
        </div>
        {draft ? (
          <DraftFrame html={draft} title="Draft preview" />
        ) : (
          <div className="empty">The draft app shows up here after the first answer.</div>
        )}
      </div>
    </section>
  )
}
