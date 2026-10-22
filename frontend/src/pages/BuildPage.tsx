import { useEffect, useState } from 'react'
import { useLocation, useNavigate, useSearchParams } from 'react-router-dom'
import { useQueryClient } from '@tanstack/react-query'
import { authorizedFetch } from '../api/client'
import { conversationsKey, useApps, useConversation, useConversations, useUpload } from '../api/queries'
import { ChatPanel, lastDraft, type Draft } from '../chat'
import { timeAgo } from '../lib/time'
import { DraftFrame } from '../shell/DraftFrame'
import { renderBuildData } from './ChangesCard'
import { DeployBar } from './DeployBar'
import { LatestMigration } from './MigrationCard'
import { lastMigration, migrationOf, type MigrationProposal } from './migration'
import { ReplaceBar } from './ReplaceBar'
import type { UploadToAdapt } from './UploadPage'

/**
 * Which chat the panel shows. `key` changes only when the builder switches chats, never when the server names
 * the chat that is already open (`adoptedId`) — remounting would throw away the answer being streamed. `upload` is
 * the file a new import conversation starts from (handed over by the Upload page).
 */
interface Session {
  key: string
  conversationId: string | null
  targetSlug: string | null
  adoptedId: string | null
  upload: UploadToAdapt | null
}

function sessionFrom(params: URLSearchParams, upload: UploadToAdapt | null = null): Session {
  const conversationId = params.get('c')
  const targetSlug = conversationId ? null : params.get('app')
  return {
    key: conversationId ?? `new:${targetSlug ?? ''}:${Date.now()}`,
    conversationId,
    targetSlug,
    adoptedId: null,
    upload: conversationId || targetSlug ? null : upload,
  }
}

function withoutExtension(fileName: string): string {
  return fileName.replace(/\.html?$/i, '')
}

/**
 * Conversations on the left, the chat in the middle, the live preview on the right with Deploy — or, when the chat
 * changes an existing app, Replace HTML. URL: `?c=<conversation>` or `?app=<slug>` to start changing an app; the
 * Upload page starts an import conversation through the router state. An import's preview switches between the
 * uploaded file (Before) and the adapted draft (After).
 */
export function BuildPage() {
  const [params, setParams] = useSearchParams()
  const location = useLocation()
  const navigate = useNavigate()
  const [session, setSession] = useState(() =>
    sessionFrom(params, (location.state as { upload?: UploadToAdapt } | null)?.upload ?? null),
  )
  const [draft, setDraft] = useState<Draft | null>(null)
  const [migration, setMigration] = useState<MigrationProposal | null>(null)
  const [view, setView] = useState<'before' | 'after'>('after')
  const queryClient = useQueryClient()
  const conversations = useConversations()
  const saved = useConversation(session.conversationId)
  const apps = useApps()

  // The file now lives in the session; a reload must not start the same import again.
  useEffect(() => {
    if (location.state) navigate({ pathname: location.pathname, search: location.search }, { replace: true, state: null })
  }, []) // on mount only

  // Links and back/forward change the URL without telling us; follow them unless it is the chat already open.
  useEffect(() => {
    const c = params.get('c')
    const open = c !== null && (c === session.conversationId || c === session.adoptedId)
    const sameNew = c === null && session.conversationId === null && session.adoptedId === null
      && params.get('app') === session.targetSlug
    if (!open && !sameNew) {
      setSession(sessionFrom(params))
      setDraft(null)
      setMigration(null)
      setView('after')
    }
  }, [params]) // the URL alone decides; the session it compares with is the one rendered with it

  // An opened conversation shows its last draft, and Replace offers its last data change.
  useEffect(() => {
    if (saved.data) {
      setDraft(lastDraft(saved.data))
      setMigration(lastMigration(saved.data))
    }
  }, [saved.data])

  const summary = conversations.data?.find((c) => c.id === (session.conversationId ?? session.adoptedId))
  const reworkSlug = session.targetSlug ?? summary?.targetSlug ?? draft?.targetSlug ?? null
  const reworkApp = reworkSlug ? apps.data?.find((app) => app.slug === reworkSlug) : undefined
  const storedUpload = useUpload(session.conversationId, !session.upload && !!summary?.upload)
  const original = session.upload ?? storedUpload.data ?? null
  const shown = view === 'before' && original ? original.html : (draft?.html ?? original?.html ?? null)

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
              {c.upload ? `imports ${c.upload} · ` : ''}
              {timeAgo(c.updatedAt)}
            </span>
          </button>
        ))}
      </aside>

      <div className="chat-column">
        {session.conversationId && !saved.data ? (
          <div className="empty">{saved.isError ? saved.error.message : 'Loading the conversation…'}</div>
        ) : (
          <LatestMigration.Provider value={migration?.id ?? null}>
            <ChatPanel
              key={session.key}
              endpoint="/api/chat"
              fetch={authorizedFetch}
              conversationId={session.conversationId}
              initialMessages={saved.data}
              targetSlug={session.targetSlug}
              startBody={
                session.upload ? { upload: { fileName: session.upload.fileName, html: session.upload.html } } : undefined
              }
              autoSend={
                session.upload ? `Adapt ${session.upload.fileName} to ShitCloud.\n\n${session.upload.findings}` : undefined
              }
              greeting={
                session.targetSlug
                  ? `What should change in “${reworkApp?.name ?? session.targetSlug}”? I'll read the app first.`
                  : 'Hi! Describe the app you need — e.g. "a team calendar where everyone marks office days and days off".'
              }
              placeholder="Describe the app or ask for a change…"
              onDraft={setDraft}
              onData={(part) => {
                const proposal = migrationOf(part)
                if (proposal) setMigration(proposal)
              }}
              renderData={renderBuildData}
              onConversation={({ id }) => {
                setSession((s) => ({ ...s, adoptedId: id }))
                setParams(new URLSearchParams({ c: id }), { replace: true })
                void queryClient.invalidateQueries({ queryKey: conversationsKey, exact: true })
              }}
            />
          </LatestMigration.Provider>
        )}
      </div>

      <div className="preview">
        <div className="preview-head">
          <strong>Preview</strong>
          {original && (
            <div className="segmented" role="group" aria-label="Show">
              <button type="button" className={view === 'before' ? 'active' : ''} onClick={() => setView('before')}>
                Before
              </button>
              <button type="button" className={view === 'after' ? 'active' : ''} onClick={() => setView('after')}>
                After
              </button>
            </div>
          )}
          {reworkSlug ? (
            <ReplaceBar
              slug={reworkSlug}
              name={reworkApp?.name ?? reworkSlug}
              html={draft?.html ?? null}
              migration={migration?.space === reworkSlug ? migration : null}
            />
          ) : (
            <DeployBar
              key={original?.fileName ?? ''}
              html={draft?.html ?? null}
              initialName={original ? withoutExtension(original.fileName) : ''}
            />
          )}
        </div>
        {shown ? (
          <>
            <p className="muted preview-note">
              {original && view === 'before'
                ? `The uploaded ${original.fileName} as it was — it may load files from the internet in your browser.`
                : original && !draft
                  ? 'Not adapted yet — this is still the uploaded file.'
                  : 'The preview has no data — saving and loading work after deploying.'}
            </p>
            <DraftFrame key={view} html={shown} title={view === 'before' ? 'Uploaded file' : 'Draft preview'} />
          </>
        ) : (
          <div className="empty">The draft app shows up here once the chat has written it.</div>
        )}
      </div>
    </section>
  )
}
