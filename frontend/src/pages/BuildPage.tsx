import { useEffect, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { useQueryClient } from '@tanstack/react-query'
import { authorizedFetch } from '../api/client'
import { conversationsKey, useApps, useConversation, useConversations } from '../api/queries'
import { ChatPanel, lastDraft, type Draft } from '../chat'
import { timeAgo } from '../lib/time'
import { DraftFrame } from '../shell/DraftFrame'
import { DeployBar } from './DeployBar'
import { ReplaceBar } from './ReplaceBar'

/**
 * Which chat the panel shows. `key` changes only when the builder switches chats, never when the server names
 * the chat that is already open (`adoptedId`) — remounting would throw away the answer being streamed.
 */
interface Session {
  key: string
  conversationId: string | null
  targetSlug: string | null
  adoptedId: string | null
}

function sessionFrom(params: URLSearchParams): Session {
  const conversationId = params.get('c')
  const targetSlug = conversationId ? null : params.get('app')
  return { key: conversationId ?? `new:${targetSlug ?? ''}:${Date.now()}`, conversationId, targetSlug, adoptedId: null }
}

/**
 * Conversations on the left, the chat in the middle, the live preview on the right with Deploy — or, when the chat
 * changes an existing app, Replace HTML. URL: `?c=<conversation>` or `?app=<slug>` to start changing an app.
 */
export function BuildPage() {
  const [params, setParams] = useSearchParams()
  const [session, setSession] = useState(() => sessionFrom(params))
  const [draft, setDraft] = useState<Draft | null>(null)
  const queryClient = useQueryClient()
  const conversations = useConversations()
  const saved = useConversation(session.conversationId)
  const apps = useApps()

  // Links and back/forward change the URL without telling us; follow them unless it is the chat already open.
  useEffect(() => {
    const c = params.get('c')
    const open = c !== null && (c === session.conversationId || c === session.adoptedId)
    const sameNew = c === null && session.conversationId === null && session.adoptedId === null
      && params.get('app') === session.targetSlug
    if (!open && !sameNew) {
      setSession(sessionFrom(params))
      setDraft(null)
    }
  }, [params]) // the URL alone decides; the session it compares with is the one rendered with it

  // An opened conversation shows its last draft.
  useEffect(() => {
    if (saved.data) setDraft(lastDraft(saved.data))
  }, [saved.data])

  const summary = conversations.data?.find((c) => c.id === (session.conversationId ?? session.adoptedId))
  const reworkSlug = session.targetSlug ?? summary?.targetSlug ?? draft?.targetSlug ?? null
  const reworkApp = reworkSlug ? apps.data?.find((app) => app.slug === reworkSlug) : undefined

  const open = (next: URLSearchParams) => {
    setParams(next)
  }

  return (
    <section className="build">
      <aside className="conversations">
        <button className={`conversation new${!session.conversationId && !session.adoptedId ? ' active' : ''}`}
                onClick={() => open(new URLSearchParams())}>
          + New app
        </button>
        {conversations.data?.map((c) => (
          <button
            key={c.id}
            className={`conversation${c.id === session.conversationId || c.id === session.adoptedId ? ' active' : ''}`}
            onClick={() => open(new URLSearchParams({ c: c.id }))}
          >
            <span className="conversation-title">{c.title}</span>
            <span className="muted">
              {c.targetSlug ? `changes ${c.targetSlug} · ` : ''}
              {timeAgo(c.updatedAt)}
            </span>
          </button>
        ))}
      </aside>

      <div className="chat-column">
        {session.conversationId && !saved.data ? (
          <div className="empty">{saved.isError ? saved.error.message : 'Loading the conversation…'}</div>
        ) : (
          <ChatPanel
            key={session.key}
            endpoint="/api/chat"
            fetch={authorizedFetch}
            conversationId={session.conversationId}
            initialMessages={saved.data}
            targetSlug={session.targetSlug}
            greeting={
              session.targetSlug
                ? `What should change in “${reworkApp?.name ?? session.targetSlug}”? I'll read the app first.`
                : 'Hi! Describe the app you need — e.g. "a team calendar where everyone marks office days and days off".'
            }
            placeholder="Describe the app or ask for a change…"
            onDraft={setDraft}
            onConversation={({ id }) => {
              setSession((s) => ({ ...s, adoptedId: id }))
              setParams(new URLSearchParams({ c: id }), { replace: true })
              void queryClient.invalidateQueries({ queryKey: conversationsKey, exact: true })
            }}
          />
        )}
      </div>

      <div className="preview">
        <div className="preview-head">
          <strong>Preview</strong>
          {reworkSlug ? (
            <ReplaceBar slug={reworkSlug} name={reworkApp?.name ?? reworkSlug} html={draft?.html ?? null} />
          ) : (
            <DeployBar html={draft?.html ?? null} />
          )}
        </div>
        {draft ? (
          <>
            <p className="muted preview-note">The preview has no data — saving and loading work after deploying.</p>
            <DraftFrame html={draft.html} title="Draft preview" />
          </>
        ) : (
          <div className="empty">The draft app shows up here once the chat has written it.</div>
        )}
      </div>
    </section>
  )
}
