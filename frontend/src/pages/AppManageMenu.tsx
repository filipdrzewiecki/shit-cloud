import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api } from '../api/client'
import { useArchive, useMigrations, useRestore } from '../api/queries'
import type { AppDetail } from '../api/types'
import { safeFileName, saveFile } from '../lib/download'
import { DeleteAppDialog } from './DeleteAppDialog'
import { ReplaceHtmlDialog } from './ReplaceHtmlDialog'
import { UndoMigrationDialog } from './UndoMigrationDialog'

/**
 * "⋯ Manage" on the host page. Everyone can download the app and its data (they
 * can read all of it through the app anyway); builders also change it with the
 * build chat, replace, archive, restore and delete it, browse its data and history, and undo the chat's latest data change while its copy is
 * kept.
 */
export function AppManageMenu({ detail, builder }: { detail: AppDetail; builder: boolean }) {
  const { app } = detail
  const [open, setOpen] = useState(false)
  const [dialog, setDialog] = useState<'replace' | 'delete' | 'undo' | null>(null)
  const [error, setError] = useState<string | null>(null)
  const archive = useArchive(app.slug)
  const restore = useRestore(app.slug)
  const migrations = useMigrations(app.slug, builder)
  const undoable = migrations.data?.find((m) => m.undoable) ?? null
  const menu = useRef<HTMLDivElement>(null)
  const navigate = useNavigate()

  // Close the menu on any click outside it.
  useEffect(() => {
    if (!open) return
    const close = (e: MouseEvent) => {
      if (!menu.current?.contains(e.target as Node)) setOpen(false)
    }
    document.addEventListener('mousedown', close)
    return () => document.removeEventListener('mousedown', close)
  }, [open])

  const run = (action: () => void) => () => {
    setOpen(false)
    setError(null)
    action()
  }

  const downloadHtml = () =>
    saveFile(new Blob([detail.html], { type: 'text/html;charset=utf-8' }), safeFileName(app.name, 'html'))

  const downloadData = async () => {
    try {
      const { blob, fileName } = await api.download(`/api/apps/${app.slug}/export`)
      saveFile(blob, fileName ?? `${app.slug}-data.json`)
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not download the data')
    }
  }

  const downloadCopy = async (id: number) => {
    try {
      const { blob, fileName } = await api.download(`/api/migrations/${id}/copy`)
      saveFile(blob, fileName ?? `${app.slug}-before-change-${id}.json`)
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not download the data')
    }
  }

  return (
    <div className="manage" ref={menu}>
      {(error || archive.error || restore.error) && (
        <span className="error-text">{error ?? archive.error?.message ?? restore.error?.message}</span>
      )}
      <button type="button" className="button" aria-expanded={open} onClick={() => setOpen(!open)}>
        ⋯ Manage
      </button>
      {open && (
        <div className="menu" role="menu">
          <button type="button" role="menuitem" onClick={run(downloadHtml)}>
            ⬇ Download HTML
          </button>
          <button type="button" role="menuitem" onClick={run(() => void downloadData())}>
            ⬇ Download data
          </button>
          {builder && (
            <>
              <hr />
              <button type="button" role="menuitem" onClick={run(() => navigate(`/build?app=${app.slug}`))}>
                ✨ Change with AI
              </button>
              <button type="button" role="menuitem" onClick={run(() => setDialog('replace'))}>
                ⇪ Replace HTML…
              </button>
              <button type="button" role="menuitem" onClick={run(() => navigate(`/apps/${app.slug}/data`))}>
                🕘 Data &amp; history
              </button>
              {undoable && (
                <>
                  <button type="button" role="menuitem" onClick={run(() => setDialog('undo'))}>
                    ↶ Undo data change…
                  </button>
                  <button type="button" role="menuitem" onClick={run(() => void downloadCopy(undoable.id))}>
                    ⬇ Data before the change
                  </button>
                </>
              )}
              {app.archived ? (
                <button type="button" role="menuitem" onClick={run(() => restore.mutate())}>
                  ↺ Restore
                </button>
              ) : (
                <button type="button" role="menuitem" onClick={run(() => archive.mutate())}>
                  🗄 Archive (keep data)
                </button>
              )}
              <button type="button" role="menuitem" className="danger-item" onClick={run(() => setDialog('delete'))}>
                🗑 Delete with data…
              </button>
            </>
          )}
        </div>
      )}
      {dialog === 'replace' && <ReplaceHtmlDialog app={app} onClose={() => setDialog(null)} />}
      {dialog === 'undo' && undoable && (
        <UndoMigrationDialog
          app={app}
          migration={undoable}
          onDownloadCopy={() => void downloadCopy(undoable.id)}
          onClose={() => setDialog(null)}
        />
      )}
      {dialog === 'delete' && (
        <DeleteAppDialog app={app} onDownloadData={() => void downloadData()} onClose={() => setDialog(null)} />
      )}
    </div>
  )
}
