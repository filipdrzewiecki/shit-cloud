import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { ApiError } from '../api/client'
import { useDeploy } from '../api/queries'

function deployError(error: Error): string {
  if (!(error instanceof ApiError)) return 'Could not reach ShitCloud. Try again in a moment.'
  switch (error.status) {
    case 413:
      return 'This app is too large to deploy (max 5 MB).'
    case 409:
      return 'Someone else is deploying this app right now. Try again.'
    default:
      return error.message
  }
}

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
      {deploy.isError && <span className="error-text">{deployError(deploy.error)}</span>}
    </div>
  )
}
