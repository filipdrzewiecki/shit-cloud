/**
 * Renders a user app. For the mockup the HTML goes in through `srcdoc`, which
 * gives the frame an opaque origin — so `allow-same-origin` is deliberately
 * left out: combined with srcdoc it would hand the app the platform's origin.
 *
 * The real thing serves each app from its own origin ({slug}.apps…) with CSP,
 * and only then adds `allow-same-origin` (see handoff 4.1).
 *
 * This is not only about isolation in principle: the platform origin keeps the
 * reader's credentials in localStorage (the Auth0 refresh token and the guest
 * token). An app that ran on this origin could read them and act as the reader.
 */
export function AppFrame({ html, title }: { html: string; title: string }) {
  return (
    <iframe
      className="app-frame"
      title={title}
      srcDoc={html}
      sandbox="allow-scripts allow-forms allow-modals allow-popups allow-downloads"
    />
  )
}
