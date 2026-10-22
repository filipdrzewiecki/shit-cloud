import { memo, type ReactNode } from 'react'
import type { UIMessage } from 'ai'
import { Markdown } from './Markdown'
import { toolLine, type ToolPartLike } from './toolLabels'

// Every component here is declared at module level: components created inside a render re-mount on every
// streamed chunk, which can freeze the tab without any error.

type Part = UIMessage['parts'][number]

/** A data part (`data-<name>`) the chat itself does not know. */
export interface DataPartLike {
  type: string
  id?: string
  data: unknown
}

/** Draws data parts of the host's own kinds inside the conversation; null for kinds it does not draw. */
export type RenderData = (part: DataPartLike) => ReactNode

function ToolChip({ part }: { part: ToolPartLike }) {
  const line = toolLine(part)
  return <div className={`sc-chat-tool sc-chat-tool-${line.state}`}>{line.text}</div>
}

function PartView({ part, renderData }: { part: Part; renderData?: RenderData }) {
  if (part.type === 'text') return <Markdown text={part.text} />
  if (part.type.startsWith('tool-') || part.type === 'dynamic-tool') return <ToolChip part={part as ToolPartLike} />
  if (part.type === 'data-draft') return <div className="sc-chat-note">Draft updated — see the preview.</div>
  if (part.type.startsWith('data-') && renderData) return renderData(part as DataPartLike) ?? null
  return null
}

/**
 * One message; re-renders only when its own object changes (the SDK replaces the streaming message only) — so
 * `renderData` must be a stable function.
 */
export const MessageView = memo(function MessageView({ message, renderData }: { message: UIMessage; renderData?: RenderData }) {
  if (message.role === 'user') {
    const text = message.parts.map((part) => (part.type === 'text' ? part.text : '')).join('')
    return <div className="sc-chat-message sc-chat-user">{text}</div>
  }
  return (
    <div className="sc-chat-message sc-chat-assistant">
      {message.parts.map((part, i) => (
        <PartView key={i} part={part} renderData={renderData} />
      ))}
    </div>
  )
})
