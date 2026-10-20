/**
 * The draft as the preview loads it: with `<base href>` at the apps origin, so `/_lib/…` (the library mirror the
 * apps are told to use) loads from there instead of from the platform's domain. Only the preview's copy changes;
 * the HTML that gets deployed stays as it is (deployed apps run on the apps origin and must not have `<base>`).
 */
export function withBaseHref(html: string, baseUrl: string | undefined): string {
  if (!baseUrl) return html
  const base = `<base href="${baseUrl.replace(/"/g, '&quot;')}">`
  const head = /<head(\s[^>]*)?>/i.exec(html)
  if (head) {
    const end = head.index + head[0].length
    return html.slice(0, end) + base + html.slice(end)
  }
  // No <head>: the parser puts a leading <base> into the implied head, before anything that loads.
  const doctype = /^\s*<!doctype[^>]*>/i.exec(html)
  return doctype ? doctype[0] + base + html.slice(doctype[0].length) : base + html
}
