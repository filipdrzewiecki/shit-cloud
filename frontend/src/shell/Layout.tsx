import { useAuth0 } from '@auth0/auth0-react'
import { useQueryClient } from '@tanstack/react-query'
import { NavLink, Outlet } from 'react-router-dom'
import { useMe } from '../api/queries'
import { clearGuest } from '../auth/guestSession'

export function Layout() {
  const me = useMe()
  const { isAuthenticated, logout } = useAuth0()
  const queryClient = useQueryClient()

  const signOut = () => {
    queryClient.clear()
    clearGuest()
    if (isAuthenticated) {
      void logout({ logoutParams: { returnTo: `${window.location.origin}/sign-in` } })
    } else {
      // A full load rather than navigate(): the sign-in gate re-renders as soon as the session is
      // cleared and would otherwise record this page as where to come back to. It also drops any
      // in-memory state the previous reader left behind.
      window.location.assign('/sign-in')
    }
  }

  return (
    <div className="shell">
      <header className="topbar">
        <NavLink to="/" className="brand" title="Simple Handy Internal Tools">
          <span aria-hidden>☁️</span> S.H.I.T. Cloud
        </NavLink>
        <nav className="nav">
          <NavLink to="/" end>
            Apps
          </NavLink>
          {me.data?.builder && <NavLink to="/build">Build with AI</NavLink>}
          {me.data?.builder && <NavLink to="/upload">Upload</NavLink>}
          {me.data?.builder && <NavLink to="/guide">Guide for your AI</NavLink>}
        </nav>
        <div className="me">
          {me.data && (
            <>
              <span className="avatar">{me.data.displayName[0]}</span>
              <span>{me.data.displayName}</span>
              {me.data.guest && <span className="badge muted-badge">guest</span>}
              {me.data.admin ? (
                <span className="badge">admin</span>
              ) : (
                me.data.builder && <span className="badge">builder</span>
              )}
            </>
          )}
          {me.isError && <span className="error-text">API unreachable</span>}
          <button type="button" className="link-button" onClick={signOut}>
            Sign out
          </button>
        </div>
      </header>
      <main className="content">
        <Outlet />
      </main>
    </div>
  )
}
