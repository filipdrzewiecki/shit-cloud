/**
 * What in an uploaded HTML file will not work on ShitCloud as is — the platform's CSP blocks external files and
 * network calls, and browser storage keeps data in one browser only. A quick look without AI: text patterns, so it
 * runs anywhere (tests too) and may miss or over-count; it is a checklist for the builder and the chat, not a gate.
 */
export type FindingKind =
  | 'externalScript'
  | 'externalStyle'
  | 'externalFont'
  | 'externalImage'
  | 'network'
  | 'storage'
  | 'formPost'
  | 'base'

export interface Finding {
  kind: FindingKind
  label: string
  count: number
  /** Where external files come from (host names), for the external kinds. */
  hosts: string[]
}

const URL_ATTR = String.raw`=\s*["']?((?:https?:)?\/\/[^"'\s>]+)`

function hostOf(url: string): string {
  const match = /^(?:https?:)?\/\/([^/:?#]+)/i.exec(url)
  return match ? match[1].toLowerCase() : url
}

function urls(html: string, pattern: RegExp): string[] {
  return [...html.matchAll(pattern)].map((match) => match[1])
}

function count(html: string, pattern: RegExp): number {
  return [...html.matchAll(pattern)].length
}

function external(kind: FindingKind, noun: string, found: string[]): Finding[] {
  if (found.length === 0) return []
  const hosts = [...new Set(found.map(hostOf))]
  const label = `${found.length} external ${noun}${found.length === 1 ? '' : 's'} (${hosts.join(', ')})`
  return [{ kind, label, count: found.length, hosts }]
}

function usage(kind: FindingKind, label: string, n: number): Finding[] {
  return n === 0 ? [] : [{ kind, label: `${label} (${n}×)`, count: n, hosts: [] }]
}

export function scan(html: string): Finding[] {
  const fontHost = /fonts\.(googleapis|gstatic)\.com|use\.typekit\.net|fonts\.bunny\.net/i
  const links = urls(html, new RegExp(String.raw`<link\b[^>]*\bhref` + URL_ATTR, 'gi'))
  const imports = urls(html, /@import\s+(?:url\()?\s*["']?((?:https?:)?\/\/[^"')\s;]+)/gi)
  const styleUrls = [...links, ...imports]
  const fonts = [
    ...styleUrls.filter((url) => fontHost.test(url)),
    ...urls(html, /url\(\s*["']?((?:https?:)?\/\/[^"')\s]+\.(?:woff2?|ttf|otf|eot))/gi),
  ]
  const images = [
    ...urls(html, new RegExp(String.raw`<img\b[^>]*\bsrc` + URL_ATTR, 'gi')),
    ...urls(html, /url\(\s*["']?((?:https?:)?\/\/[^"')\s]+\.(?:png|jpe?g|gif|svg|webp))/gi),
  ]

  return [
    ...external('externalScript', 'script', urls(html, new RegExp(String.raw`<script\b[^>]*\bsrc` + URL_ATTR, 'gi'))),
    ...external('externalStyle', 'stylesheet', styleUrls.filter((url) => !fontHost.test(url))),
    ...external('externalFont', 'font', fonts),
    ...external('externalImage', 'image', images),
    ...usage(
      'network',
      'network calls — fetch, XMLHttpRequest, WebSocket, EventSource or sendBeacon',
      count(html, /\bfetch\s*\(|\bXMLHttpRequest\b|\bWebSocket\b|\bEventSource\b|\bsendBeacon\b/g),
    ),
    ...usage(
      'storage',
      'data kept in the browser — localStorage, sessionStorage or IndexedDB, only in one browser',
      count(html, /\blocalStorage\b|\bsessionStorage\b|\bindexedDB\b/g),
    ),
    ...usage('formPost', 'forms that post to a server', count(html, /<form\b[^>]*\baction\s*=/gi)),
    ...usage('base', '<base> tag', count(html, /<base\b/gi)),
  ]
}

/** The findings as a list for the chat's first message. */
export function findingsText(findings: Finding[]): string {
  if (findings.length === 0) return 'A quick check found nothing the platform blocks.'
  return ['A quick check found what will not work as is:', ...findings.map((f) => `- ${f.label}`)].join('\n')
}
