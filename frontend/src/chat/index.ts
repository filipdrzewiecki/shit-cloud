// The build chat's UI. Copyable as a folder: it imports nothing else from the GUI (see boundary.test.ts); the host
// page hands it a fetch that knows the server.
export { ChatPanel, type ChatPanelProps } from './ChatPanel'
export { lastDraft, type Draft } from './drafts'
export type { ChatFetch } from './serverChat'
