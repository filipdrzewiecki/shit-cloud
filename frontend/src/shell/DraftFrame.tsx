/**
 * Previews HTML that is not deployed yet (Build and Upload). The HTML goes in
 * through `srcdoc`, which gives the frame an opaque origin — so
 * `allow-same-origin` is deliberately left out: combined with srcdoc it would
 * hand the draft the platform's origin and with it the reader's credentials.
 *
 * The cost: a draft has no storage of its own, so an app that relies on
 * localStorage only fully works once deployed (then it runs in AppFrame).
 */
export function DraftFrame({ html, title }: { html: string; title: string }) {
  return (
    <iframe
      className="app-frame"
      title={title}
      srcDoc={html}
      sandbox="allow-scripts allow-forms allow-modals allow-popups allow-downloads"
    />
  )
}
