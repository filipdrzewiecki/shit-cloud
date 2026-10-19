import { Auth0Provider } from '@auth0/auth0-react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { StrictMode, type ReactNode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter, useNavigate } from 'react-router-dom'
import { App } from './App'
import { ApiError } from './api/client'
import './styles/tokens.css'
import './styles/app.css'

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      // A 4xx will not change on retry; only transport and server errors might.
      retry: (failureCount, error) => (error instanceof ApiError && error.status < 500 ? false : failureCount < 2),
      refetchOnWindowFocus: false,
    },
  },
})

const root = document.getElementById('root')
if (!root) throw new Error('Missing #root')

/**
 * Builder sign-in. The provider sits inside the router because
 * `onRedirectCallback` needs `useNavigate()` to put the builder back where they
 * were going when Auth0 hands the browser back.
 *
 * Sessions survive reloads through refresh tokens in localStorage: iframe
 * silent auth needs third-party cookies, which browsers increasingly block, so
 * the iframe fallback is off rather than masking a broken refresh-token setup.
 * A durable credential on this origin is acceptable only because user apps
 * never run on it (see AppFrame and DraftFrame). Rotation with reuse detection is configured
 * in Auth0 to bound a stolen token.
 */
function Auth0ProviderWithNavigate({ children }: { children: ReactNode }) {
  const navigate = useNavigate()
  return (
    <Auth0Provider
      domain={import.meta.env.VITE_AUTH0_DOMAIN}
      clientId={import.meta.env.VITE_AUTH0_CLIENT_ID}
      useRefreshTokens
      useRefreshTokensFallback={false}
      cacheLocation="localstorage"
      authorizationParams={{
        redirect_uri: window.location.origin,
        audience: import.meta.env.VITE_AUTH0_AUDIENCE,
        scope: 'openid profile email offline_access',
      }}
      onRedirectCallback={(appState) => navigate(appState?.returnTo ?? '/', { replace: true })}
    >
      {children}
    </Auth0Provider>
  )
}

createRoot(root).render(
  <StrictMode>
    <BrowserRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <Auth0ProviderWithNavigate>
        <QueryClientProvider client={queryClient}>
          <App />
        </QueryClientProvider>
      </Auth0ProviderWithNavigate>
    </BrowserRouter>
  </StrictMode>,
)
