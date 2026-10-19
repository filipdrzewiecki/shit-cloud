import { forwardRef } from 'react'

/**
 * Runs a deployed app from its own origin (the API's apps port), never from
 * this one.
 *
 * `allow-same-origin` is safe here only because `src` is the apps origin: the
 * app gets *that* origin — its own localStorage, cookies and IndexedDB — and
 * nothing of the platform's. The apps origin sends a CSP that blocks every
 * network call, so what an app stores cannot leave it.
 *
 * Never combine `allow-same-origin` with `srcdoc` or a platform URL: the app
 * would run as the platform and could read the reader's credentials in
 * localStorage (the Auth0 refresh token and the guest token). Drafts that have
 * no URL yet go through DraftFrame.
 *
 * The ref reaches the iframe so the host page's bridge can tell this app's
 * messages from any other window's (see useAppBridge).
 */
export const AppFrame = forwardRef<HTMLIFrameElement, { src: string; title: string }>(function AppFrame(
  { src, title },
  ref,
) {
  return (
    <iframe
      ref={ref}
      className="app-frame"
      title={title}
      src={src}
      sandbox="allow-scripts allow-same-origin allow-forms allow-modals allow-popups allow-downloads"
    />
  )
})
