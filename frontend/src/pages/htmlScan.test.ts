import { describe, expect, it } from 'vitest'
import { findingsText, scan } from './htmlScan'

const PLANNER = `<!doctype html><html><head>
<script src="https://code.jquery.com/jquery-3.7.1.min.js"></script>
<script src="//cdn.jsdelivr.net/npm/moment"></script>
<script src="/_lib/alpine.min.js"></script>
<link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/bootstrap/dist/css/bootstrap.min.css">
<link href="https://fonts.googleapis.com/css2?family=Inter" rel="stylesheet">
<style>@import url('https://example.com/theme.css'); body { background: url(https://img.example.com/bg.png) }</style>
</head><body>
<img src="https://img.example.com/logo.png"><img src="data:image/png;base64,xx">
<form action="https://forms.example.com/send"></form>
<script>
  const tasks = JSON.parse(localStorage.getItem('tasks') || '[]')
  localStorage.setItem('tasks', JSON.stringify(tasks))
  fetch('https://api.example.com/weather').then(r => r.json())
</script>
</body></html>`

describe('scan', () => {
  it('lists what the platform blocks, with where external files come from', () => {
    const findings = scan(PLANNER)
    const byKind = Object.fromEntries(findings.map((f) => [f.kind, f]))

    expect(byKind.externalScript).toMatchObject({ count: 2, hosts: ['code.jquery.com', 'cdn.jsdelivr.net'] })
    expect(byKind.externalStyle).toMatchObject({ count: 2, hosts: ['cdn.jsdelivr.net', 'example.com'] })
    expect(byKind.externalFont).toMatchObject({ count: 1, hosts: ['fonts.googleapis.com'] })
    expect(byKind.externalImage).toMatchObject({ count: 2, hosts: ['img.example.com'] })
    expect(byKind.network.count).toBe(1)
    expect(byKind.storage.count).toBe(2)
    expect(byKind.formPost.count).toBe(1)
    expect(byKind.base).toBeUndefined()
    expect(byKind.externalScript.label).toBe('2 external scripts (code.jquery.com, cdn.jsdelivr.net)')
  })

  it('finds nothing in a file that follows the rules', () => {
    const ok = '<html><head><script src="/_lib/tailwind.js"></script></head><body><script>shitcloud.db.find("x")</script></body></html>'
    expect(scan(ok)).toEqual([])
    expect(findingsText([])).toBe('A quick check found nothing the platform blocks.')
  })

  it('writes the findings as a list for the chat', () => {
    expect(findingsText(scan('<html><base href="/"><script>fetch("/x")</script></html>'))).toBe(
      'A quick check found what will not work as is:\n'
        + '- network calls — fetch, XMLHttpRequest, WebSocket, EventSource or sendBeacon (1×)\n'
        + '- <base> tag (1×)',
    )
  })
})
