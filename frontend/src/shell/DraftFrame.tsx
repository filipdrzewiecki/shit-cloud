import { usePlatform } from '../api/queries'
import { withBaseHref } from './draftHtml'

/**
 * Previews HTML that is not deployed yet (Build, Upload, Replace). The HTML goes in
 * through `srcdoc`, which gives the frame an opaque origin — so
 * `allow-same-origin` is deliberately left out: combined with srcdoc it would
 * hand the draft the platform's origin and with it the reader's credentials.
 *
 * `<base href>` points the draft's relative URLs at the apps origin, so the
 * library mirror loads; the origin stays opaque.
 *
 * The cost: a draft has no storage of its own and no SDK, so an app's data
 * only works once deployed (then it runs in AppFrame).
 */
export function DraftFrame({ html, title }: { html: string; title: string }) {
  const platform = usePlatform()
  return (
    <iframe
      className="app-frame"
      title={title}
      srcDoc={withBaseHref(html, platform.data?.libraryBaseUrl)}
      sandbox="allow-scripts allow-forms allow-modals allow-popups allow-downloads"
    />
  )
}
