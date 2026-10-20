/** A tool call as the chat shows it: one short line, never the tool's raw input (save_draft's is a whole app). */
export interface ToolLine {
  text: string
  state: 'busy' | 'done' | 'error'
}

export interface ToolPartLike {
  type: string
  state?: string
  input?: unknown
  output?: unknown
  errorText?: string
}

function field(value: unknown, name: string): unknown {
  return typeof value === 'object' && value !== null ? (value as Record<string, unknown>)[name] : undefined
}

export function toolLine(part: ToolPartLike): ToolLine {
  const name = part.type.startsWith('tool-') ? part.type.slice('tool-'.length) : part.type
  if (part.state === 'output-error') {
    return { text: `${describe(name, part, 'failed')}: ${part.errorText ?? 'error'}`, state: 'error' }
  }
  if (part.state === 'output-available') return { text: describe(name, part, 'done'), state: 'done' }
  return { text: describe(name, part, 'busy'), state: 'busy' }
}

function describe(name: string, part: ToolPartLike, phase: 'busy' | 'done' | 'failed'): string {
  switch (name) {
    case 'save_draft': {
      if (phase === 'busy') return 'Saving the app…'
      if (phase === 'failed') return 'Could not save the app'
      const bytes = field(part.output, 'bytes')
      return typeof bytes === 'number' ? `Saved the app (${Math.max(1, Math.round(bytes / 1024))} KB)` : 'Saved the app'
    }
    case 'get_app': {
      const slug = field(part.input, 'slug')
      const app = typeof slug === 'string' ? slug : 'the app'
      if (phase === 'busy') return `Reading ${app}…`
      return phase === 'failed' ? `Could not read ${app}` : `Read ${app}`
    }
    default:
      return phase === 'busy' ? `${name}…` : phase === 'failed' ? `${name} failed` : `${name}: done`
  }
}
