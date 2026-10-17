export interface AppSummary {
  slug: string
  name: string
  description: string | null
  owner: string
  lastUsedAt: string
  archived: boolean
  version: number
}

export interface AppDetail {
  app: AppSummary
  html: string
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
