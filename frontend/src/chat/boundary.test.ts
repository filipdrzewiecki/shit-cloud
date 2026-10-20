import { describe, expect, it } from 'vitest'

const sources = import.meta.glob<string>('./*.{ts,tsx}', { query: '?raw', import: 'default', eager: true })
const LIBRARIES = new Set(['react', 'ai', '@ai-sdk/react', 'react-markdown', 'vitest'])
/** `… from 'x'` (imports and re-exports) and bare `import 'x'`. */
const MODULE = /\bfrom\s+['"]([^'"]+)['"]|^\s*import\s+['"]([^'"]+)['"]/gm

/** The chat folder is meant to be copied into another project as it is: only its own files and its libraries. */
describe('the chat folder', () => {
  it('imports nothing else from the GUI', () => {
    const offending: string[] = []
    for (const [file, source] of Object.entries(sources)) {
      for (const [, from, bare] of source.matchAll(MODULE)) {
        const module = from ?? bare
        if (!module.startsWith('./') && !LIBRARIES.has(module)) offending.push(`${file}: ${module}`)
      }
    }
    expect(offending).toEqual([])
  })

  it('sees the folder and its imports', () => {
    expect(Object.keys(sources)).toContain('./ChatPanel.tsx')
    expect([...sources['./ChatPanel.tsx'].matchAll(MODULE)].map(([, from, bare]) => from ?? bare)).toContain('@ai-sdk/react')
  })
})
