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
    case 'edit_draft': {
      if (phase === 'busy') return 'Editing the app…'
      if (phase === 'failed') return 'Could not edit the app'
      const edited = field(part.output, 'edited')
      const bytes = field(part.output, 'bytes')
      const changes = typeof edited === 'number' ? `${edited} ${edited === 1 ? 'change' : 'changes'}` : null
      const size = typeof bytes === 'number' ? `${Math.max(1, Math.round(bytes / 1024))} KB` : null
      const details = [changes, size].filter(Boolean).join(', ')
      return details ? `Edited the app (${details})` : 'Edited the app'
    }
    case 'list_spaces': {
      if (phase === 'busy') return 'Looking at the apps…'
      if (phase === 'failed') return 'Could not list the apps'
      const spaces = field(part.output, 'spaces')
      return typeof spaces === 'number' ? `Listed ${spaces} ${spaces === 1 ? 'app' : 'apps'} and their data` : 'Listed the apps'
    }
    case 'sample_docs': {
      const space = field(part.input, 'space')
      const collection = field(part.input, 'collection')
      const where = typeof space === 'string' && typeof collection === 'string' ? `${space} / ${collection}` : 'a collection'
      if (phase === 'busy') return `Looking at documents of ${where}…`
      if (phase === 'failed') return `Could not read documents of ${where}`
      const docs = field(part.output, 'docs')
      return typeof docs === 'number'
        ? `Looked at ${docs} ${docs === 1 ? 'document' : 'documents'} of ${where}`
        : `Looked at documents of ${where}`
    }
    case 'get_deps': {
      const space = field(part.input, 'space')
      const app = typeof space === 'string' ? space : 'the app'
      if (phase === 'busy') return `Checking who uses ${app}'s data…`
      if (phase === 'failed') return `Could not check who uses ${app}'s data`
      const others = field(part.output, 'otherApps')
      if (typeof others !== 'number') return `Checked who uses ${app}'s data`
      if (others === 0) return `Checked who uses ${app}'s data (no other apps)`
      return `Checked who uses ${app}'s data (${others} other ${others === 1 ? 'app' : 'apps'})`
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
