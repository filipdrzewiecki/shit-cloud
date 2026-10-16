import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useDeploy } from '../api/queries'

/**
 * Name + Deploy, shared by the build and upload screens. Deploy sits outside
 * the chat on purpose: the human decides when a draft becomes an app.
 */
export function DeployBar({ html, initialName = '' }: { html: string | null; initialName?: string }) {
  const [name, setName] = useState(initialName)
  const deploy = useDeploy()
  const navigate = useNavigate()

  const submit = () => {
    if (!html) return
    deploy.mutate({ name, html }, { onSuccess: (app) => navigate(`/apps/${app.slug}`) })
  }

  return (
    <div className="deploy-bar">
      <input placeholder="App name, e.g. Kalendarz urlopów" value={name} onChange={(e) => setName(e.target.value)} />
      <button className="primary" disabled={!html || !name.trim() || deploy.isPending} onClick={submit}>
        {deploy.isPending ? 'Deploying…' : 'Deploy'}
      </button>
      {deploy.isError && <span className="error-text">{deploy.error.message}</span>}
    </div>
  )
}
