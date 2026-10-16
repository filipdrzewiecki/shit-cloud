import { NavLink, Outlet } from 'react-router-dom'
import { useMe } from '../api/queries'

export function Layout() {
  const me = useMe()

  return (
    <div className="shell">
      <header className="topbar">
        <NavLink to="/" className="brand">
          <span aria-hidden>☁️</span> ShitCloud
        </NavLink>
        <nav className="nav">
          <NavLink to="/" end>
            Apps
          </NavLink>
          {me.data?.builder && <NavLink to="/build">Build with AI</NavLink>}
          {me.data?.builder && <NavLink to="/upload">Upload</NavLink>}
        </nav>
        <div className="me">
          {me.data && (
            <>
              <span className="avatar">{me.data.displayName[0]}</span>
              <span>{me.data.displayName}</span>
              {me.data.builder && <span className="badge">builder</span>}
            </>
          )}
          {me.isError && <span className="error-text">API unreachable</span>}
        </div>
      </header>
      <main className="content">
        <Outlet />
      </main>
    </div>
  )
}
