import ReactMarkdown, { type Components } from 'react-markdown'

/** Links leave the chat in a new tab. Raw HTML in an answer is never rendered (react-markdown's default). */
const components: Components = {
  a: ({ href, children }) => (
    <a href={href} target="_blank" rel="noopener noreferrer">
      {children}
    </a>
  ),
}

export function Markdown({ text }: { text: string }) {
  return <ReactMarkdown components={components}>{text}</ReactMarkdown>
}
