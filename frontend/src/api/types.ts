export interface AppSummary {
  slug: string
  name: string
  description: string | null
  owner: string
  lastUsedAt: string
  archived: boolean
  version: number
  /** When a builder archived the app; null while it is live. */
  archivedAt: string | null
  archivedBy: string | null
}

export interface AppUsage {
  documents: number
  collections: number
  versions: number
}

export interface DeleteResult {
  documents: number
  versions: number
  history: number
}

export interface AppDetail {
  app: AppSummary
  html: string
  /** Where the app runs, on the apps origin. The GUI never builds app URLs itself. */
  frameUrl: string
}

export interface DeployRequest {
  name: string
  description?: string
  html: string
}

export interface Me {
  id: string
  displayName: string
  groups: string[]
  builder: boolean
  admin: boolean
  guest: boolean
}

export interface GuestLoginRequest {
  password: string
  name: string
}

export interface GuestToken {
  token: string
  expiresAt: string
}

export interface ChatMessage {
  role: 'user' | 'assistant'
  content: string
}

export interface ChatResponse {
  reply: ChatMessage
  draftHtml: string | null
}

/** A document of an app, as the Data API returns it. `id` is the app's own id. */
export interface DocView {
  id: string
  data: Record<string, unknown>
  version: number
  createdBy: string
  createdAt: string
  updatedBy: string
  updatedAt: string
}

export interface DataQuery {
  filter?: Record<string, unknown>
  sort?: Record<string, number>
  limit?: number
}
