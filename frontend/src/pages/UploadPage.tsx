import { useMemo, useState, type ChangeEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { DraftFrame } from '../shell/DraftFrame'
import { DeployBar } from './DeployBar'
import { findingsText, scan } from './htmlScan'

/** The chat adapts files up to this size (the server's `chat.max-upload-size`); bigger ones deploy as is only. */
export const MAX_ADAPT_BYTES = 300 * 1024

/** What the Build page needs to start adapting an uploaded file (router state). */
export interface UploadToAdapt {
  fileName: string
  html: string
  findings: string
}

/**
 * Upload a single HTML file from disk. A quick check lists what will not work on the platform; "Adapt with AI"
 * hands the file to the build chat, which fixes that and lists its changes. "Deploy as is" stays for files that
 * work unchanged (or are too big for the chat).
 */
export function UploadPage() {
  const [html, setHtml] = useState<string | null>(null)
  const [fileName, setFileName] = useState('')
  const navigate = useNavigate()

  const pick = async (e: ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0]
    if (!file) return
    setFileName(file.name)
    setHtml(await file.text())
  }

  const findings = useMemo(() => (html ? scan(html) : []), [html])
  const bytes = html ? new Blob([html]).size : 0
  const tooBig = bytes > MAX_ADAPT_BYTES
  const appName = fileName.replace(/\.html?$/i, '')

  const adapt = () => {
    if (!html) return
    const upload: UploadToAdapt = { fileName, html, findings: findingsText(findings) }
    navigate('/build', { state: { upload } })
  }

  return (
    <section>
      <div className="page-head">
        <div>
          <h1>Upload HTML</h1>
          <p className="muted">
            Any HTML that runs in a browser. The AI adapts it to the platform — no CDNs, no external calls, data shared
            by the team instead of kept in one browser — and lists what it changed before you deploy.
          </p>
        </div>
      </div>

      <label className="dropzone">
        <input type="file" accept=".html,.htm,text/html" onChange={pick} />
        {fileName ? (
          <span>
            <strong>{fileName}</strong> loaded ({Math.max(1, Math.round(bytes / 1024))} KB) — pick another file to replace it
          </span>
        ) : (
          <span>Click to choose an .html file</span>
        )}
      </label>

      {html && (
        <>
          <div className="upload-check">
            {findings.length === 0 ? (
              <p>A quick check found nothing the platform blocks — it may work as is.</p>
            ) : (
              <>
                <p>
                  <strong>Will not work as is:</strong>
                </p>
                <ul>
                  {findings.map((finding) => (
                    <li key={finding.kind}>{finding.label}</li>
                  ))}
                </ul>
              </>
            )}
            <div className="upload-actions">
              <button type="button" className="primary" disabled={tooBig} onClick={adapt}>
                ✨ Adapt with AI
              </button>
              {tooBig && (
                <span className="error-text">
                  The AI adapts files up to {MAX_ADAPT_BYTES / 1024} KB; this one can only be deployed as is.
                </span>
              )}
            </div>
          </div>
          <div className="upload-preview">
            <div className="preview-head">
              <strong>Preview (as is)</strong>
              <span className="muted">Deploy as is:</span>
              <DeployBar key={fileName} html={html} initialName={appName} />
            </div>
            <DraftFrame html={html} title="Upload preview" />
          </div>
        </>
      )}
    </section>
  )
}
