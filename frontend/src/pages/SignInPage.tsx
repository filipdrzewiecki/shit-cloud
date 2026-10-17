import { useAuth0 } from '@auth0/auth0-react'
import { useState, type FormEvent } from 'react'
import { Navigate, useSearchParams } from 'react-router-dom'
import { ApiError } from '../api/client'
import { useGuestSignIn } from '../api/queries'
import { useGuestSession } from '../auth/guestSession'
import { safeReturnTo } from '../auth/returnTo'

/** Without a client id the Auth0 tenant is not set up yet, and the button would only lead to an error page. */
const BUILDER_SIGN_IN_AVAILABLE = Boolean(import.meta.env.VITE_AUTH0_CLIENT_ID)

function guestError(error: Error): string {
  if (!(error instanceof ApiError)) return 'Could not reach ShitCloud. Try again in a moment.'
  switch (error.status) {
    case 401:
      return 'Wrong password.'
    case 400:
      return 'Enter your name (up to 40 characters).'
    case 404:
      return 'Password sign-in is turned off.'
    case 429:
      return 'Too many failed attempts. Wait 15 minutes and try again.'
    default:
      return error.message
  }
}

/**
 * The front door. Most people use the shared password and only open apps;
 * builders and admins sign in through Auth0 to create them.
 */
export function SignInPage() {
  const [params] = useSearchParams()
  const returnTo = safeReturnTo(params.get('returnTo'))
  const { isAuthenticated, loginWithRedirect } = useAuth0()
  const guest = useGuestSession()
  const signIn = useGuestSignIn()
  const [password, setPassword] = useState('')
  const [name, setName] = useState('')

  if (isAuthenticated || guest) return <Navigate to={returnTo} replace />

  const submit = (e: FormEvent) => {
    e.preventDefault()
    signIn.mutate({ password, name: name.trim() })
  }

  return (
    <main className="sign-in">
      <div className="sign-in-card">
        <h1>
          <span aria-hidden>☁️</span> ShitCloud
        </h1>
        <p className="muted">Small tools your team built. Sign in with the team password to use them.</p>

        <form onSubmit={submit} className="sign-in-form">
          <label>
            Your name
            <input value={name} onChange={(e) => setName(e.target.value)} maxLength={40} autoComplete="name" required />
          </label>
          <label>
            Team password
            <input
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              autoComplete="current-password"
              required
            />
          </label>
          {signIn.isError && <p className="error-text">{guestError(signIn.error)}</p>}
          <button type="submit" className="primary" disabled={signIn.isPending}>
            {signIn.isPending ? 'Signing in…' : 'Sign in'}
          </button>
        </form>

        <div className="sign-in-builder">
          <span className="muted">Building apps?</span>
          <button
            type="button"
            disabled={!BUILDER_SIGN_IN_AVAILABLE}
            title={BUILDER_SIGN_IN_AVAILABLE ? undefined : 'Auth0 is not configured yet'}
            onClick={() => void loginWithRedirect({ appState: { returnTo } })}
          >
            Builder sign-in
          </button>
        </div>
      </div>
    </main>
  )
}
