import { useState } from 'react'
import guide from '../guide/builder-guide.md?raw'

/**
 * The rules an app must follow, written for an AI assistant: builders paste it into whatever assistant they
 * use, so apps made outside ShitCloud's own chat work here on the first try. Kept as Markdown, the format
 * those assistants read best.
 */
export function BuilderGuidePage() {
  const [copied, setCopied] = useState(false)

  const copy = async () => {
    await navigator.clipboard.writeText(guide)
    setCopied(true)
    setTimeout(() => setCopied(false), 2000)
  }

  const download = () => {
    const link = document.createElement('a')
    link.href = URL.createObjectURL(new Blob([guide], { type: 'text/markdown' }))
    link.download = 'shitcloud-app-guide.md'
    link.click()
    URL.revokeObjectURL(link.href)
  }

  return (
    <section>
      <div className="page-head">
        <div>
          <h1>Guide for your AI</h1>
          <p className="muted">
            Building an app with ChatGPT, Claude, Copilot or another assistant? Give it these instructions first —
            they describe what ShitCloud allows and how apps store shared data. Then upload the HTML it writes.
          </p>
        </div>
        <div className="actions">
          <button type="button" className="button primary" onClick={copy}>
            {copied ? 'Copied ✓' : 'Copy instructions'}
          </button>
          <button type="button" className="button" onClick={download}>
            Download .md
          </button>
        </div>
      </div>
      <pre className="guide">{guide}</pre>
    </section>
  )
}
