import { memo } from 'react'
import type { UIMessage } from 'ai'
import { Markdown } from './Markdown'
import { toolLine, type ToolPartLike } from './toolLabels'

// Every component here is declared at module level: components created inside a render re-mount on every
// streamed chunk, which can freeze the tab without any error.

type Part = UIMessage['parts'][number]

function ToolChip({ part }: { part: ToolPartLike }) {
  const line = toolLine(part)
  return <div className={`sc-chat-tool sc-chat-tool-${line.state}`}>{line.text}</div>
}

function PartView({ part }: { part: Part }) {
  if (part.type === 'text') return <Markdown text={part.text} />
  if (part.type.startsWith('tool-') || part.type === 'dynamic-tool') return <ToolChip part={part as ToolPartLike} />
  if (part.type === 'data-draft') return <div className="sc-chat-note">Draft updated — see the preview.</div>
  return null
}

/** One message; re-renders only when its own object changes (the SDK replaces the streaming message only). */
export const MessageView = memo(function MessageView({ message }: { message: UIMessage }) {
  if (message.role === 'user') {
    const text = message.parts.map((part) => (part.type === 'text' ? part.text : '')).join('')
    return <div className="sc-chat-message sc-chat-user">{text}</div>
  }
  return (
    <div className="sc-chat-message sc-chat-assistant">
      {message.parts.map((part, i) => (
        <PartView key={i} part={part} />
      ))}
    </div>
  )
})
