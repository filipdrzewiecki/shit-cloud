import { useAuth0 } from '@auth0/auth0-react'
import { useQueryClient } from '@tanstack/react-query'
import { useEffect, type ReactNode } from 'react'
import { Navigate, Route, Routes, useLocation, useNavigate } from 'react-router-dom'
import { setIdpTokenProvider, setUnauthenticatedHandler } from './api/client'
import { useMe } from './api/queries'
import { clearGuest, useGuestSession } from './auth/guestSession'
import { signInPath } from './auth/returnTo'
import { AppHostPage } from './pages/AppHostPage'
import { AppListPage } from './pages/AppListPage'
import { BuildPage } from './pages/BuildPage'
import { SignInPage } from './pages/SignInPage'
import { UploadPage } from './pages/UploadPage'
import { Layout } from './shell/Layout'

export function App() {
  const { isLoading, isAuthenticated, getAccessTokenSilently, logout } = useAuth0()
  const guest = useGuestSession()
  const navigate = useNavigate()
  const queryClient = useQueryClient()

  // Set during render, not in an effect: child queries start in their own
  // effects, which run before this component's, and must already see the token.
  setIdpTokenProvider(isAuthenticated ? () => getAccessTokenSilently() : null)

  // A lost session (expired guest token, revoked refresh token, changed team
  // password) surfaces as a 401 on whatever call happens next. Both sessions are
  // dropped so the sign-in page does not bounce straight back here.
  useEffect(() => {
    let handling = false
    setUnauthenticatedHandler(() => {
      // Several queries in flight each report the same lost session; only the first one acts.
      if (handling || window.location.pathname === '/sign-in') return
      handling = true
      const here = window.location.pathname + window.location.search
      clearGuest()
      queryClient.clear()
      if (!isAuthenticated) {
        navigate(signInPath(here), { replace: true })
        return
      }
      // The Auth0 session must be gone before the sign-in page renders, or it still sees a signed-in
      // builder and sends them straight back here, into the same 401. The local logout is enough; the
      // Auth0 session cookie stays, so the next builder sign-in is a single click.
      void logout({ openUrl: false }).finally(() =>
        navigate(signInPath(here, 'builder-rejected'), { replace: true }),
      )
    })
  }, [isAuthenticated, logout, navigate, queryClient])

  const signedIn = isAuthenticated || guest !== null

  return (
    <Routes>
      <Route path="sign-in" element={<SignInPage />} />
      <Route
        element={
          <RequireSignIn loading={isLoading} signedIn={signedIn}>
            <Layout />
          </RequireSignIn>
        }
      >
        <Route index element={<AppListPage />} />
        <Route path="apps/:slug" element={<AppHostPage />} />
        <Route
          path="build"
          element={
            <BuildersOnly>
              <BuildPage />
            </BuildersOnly>
          }
        />
        <Route
          path="upload"
          element={
            <BuildersOnly>
              <UploadPage />
            </BuildersOnly>
          }
        />
        <Route path="*" element={<p className="muted">Nothing here.</p>} />
      </Route>
    </Routes>
  )
}

function RequireSignIn({ loading, signedIn, children }: { loading: boolean; signedIn: boolean; children: ReactNode }) {
  const { pathname, search } = useLocation()
  // Auth0 restores a builder session asynchronously; deciding before it finishes would send them to sign in.
  if (loading) return null
  if (!signedIn) return <Navigate to={signInPath(pathname + search)} replace />
  return children
}

/** The nav already hides these pages; this covers a typed URL. The API enforces the rule either way. */
function BuildersOnly({ children }: { children: ReactNode }) {
  const me = useMe()
  if (me.isPending) return null
  if (!me.data?.builder) return <p className="muted">This page is for builders. Ask a platform admin for access.</p>
  return children
}
