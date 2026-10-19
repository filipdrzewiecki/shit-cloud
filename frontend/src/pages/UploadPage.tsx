import { useState, type ChangeEvent } from 'react'
import { DraftFrame } from '../shell/DraftFrame'
import { DeployBar } from './DeployBar'

/**
 * Upload a single HTML file from disk. The real flow adds an AI adaptation step
 * with a change list and a before/after preview; this mockup deploys as-is.
 */
export function UploadPage() {
  const [html, setHtml] = useState<string | null>(null)
  const [fileName, setFileName] = useState('')

  const pick = async (e: ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0]
    if (!file) return
    setFileName(file.name.replace(/\.html?$/i, ''))
    setHtml(await file.text())
  }

  return (
    <section>
      <div className="page-head">
        <div>
          <h1>Upload HTML</h1>
          <p className="muted">
            Any HTML that runs in a browser. Later an agent will adapt it to the platform rules (no CDNs, no external
            fetch, localStorage → shared data) and show you a change list before deploy.
          </p>
        </div>
      </div>

      <label className="dropzone">
        <input type="file" accept=".html,.htm,text/html" onChange={pick} />
        {fileName ? (
          <span>
            <strong>{fileName}</strong> loaded — pick another file to replace it
          </span>
        ) : (
          <span>Click to choose an .html file</span>
        )}
      </label>

      {html && (
        <div className="upload-preview">
          <div className="preview-head">
            <strong>Preview</strong>
            <DeployBar key={fileName} html={html} initialName={fileName} />
          </div>
          <DraftFrame html={html} title="Upload preview" />
        </div>
      )}
    </section>
  )
}
