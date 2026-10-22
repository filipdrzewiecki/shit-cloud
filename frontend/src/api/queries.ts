import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { UIMessage } from 'ai'
import { saveGuest } from '../auth/guestSession'
import { api } from './client'
import type {
  AppDetail,
  AppSummary,
  AppUsage,
  ConversationSummary,
  DeleteResult,
  DependencyView,
  DeployRequest,
  GuestLoginRequest,
  GuestToken,
  Me,
  MigrationUndo,
  MigrationView,
  Platform,
} from './types'

/** Shared with the app bridge, which answers an app's `me()` from the same cache. */
export const meQuery = { queryKey: ['me'], queryFn: () => api.get<Me>('/api/me'), staleTime: Infinity }

export function useMe() {
  return useQuery(meQuery)
}

export function useApps() {
  return useQuery({ queryKey: ['apps'], queryFn: () => api.get<AppSummary[]>('/api/apps') })
}

export function useApp(slug: string) {
  return useQuery({ queryKey: ['apps', slug], queryFn: () => api.get<AppDetail>(`/api/apps/${slug}`) })
}

export function useDeploy() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (request: DeployRequest) => api.post<AppSummary>('/api/apps', request),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['apps'] }),
  })
}

/** After any change to an app: the list and the app's own page show the new state. */
function useAppMutation<TArgs, TResult>(mutationFn: (args: TArgs) => Promise<TResult>) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['apps'] }),
  })
}

export function useArchive(slug: string) {
  return useAppMutation(() => api.post<AppSummary>(`/api/apps/${slug}/archive`, {}))
}

export function useRestore(slug: string) {
  return useAppMutation(() => api.post<AppSummary>(`/api/apps/${slug}/restore`, {}))
}

/** With `migrationId`, the app's data is converted by that checked data change in the same step. */
export function useReplaceHtml(slug: string) {
  return useAppMutation(({ html, migrationId }: { html: string; migrationId?: number | null }) =>
    api.put<AppSummary>(`/api/apps/${slug}/html`, { html, migrationId: migrationId ?? null }),
  )
}

/** Data changes applied to the app (builders only), newest first. */
export function useMigrations(slug: string, enabled: boolean) {
  return useQuery({
    queryKey: ['apps', slug, 'migrations'],
    queryFn: () => api.get<MigrationView[]>(`/api/apps/${encodeURIComponent(slug)}/migrations`),
    enabled,
  })
}

export function useUndoMigration() {
  return useAppMutation((id: number) => api.post<MigrationUndo>(`/api/migrations/${id}/undo`, {}))
}

export function useDeleteApp(slug: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => api.delete<DeleteResult>(`/api/apps/${slug}?confirm=${encodeURIComponent(slug)}`),
    // Only the list: the deleted app's page is left right after, and refetching it would just 404.
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['apps'], exact: true }),
  })
}

export function useAppUsage(slug: string, enabled: boolean) {
  return useQuery({
    queryKey: ['apps', slug, 'usage'],
    queryFn: () => api.get<AppUsage>(`/api/apps/${slug}/usage`),
    enabled,
  })
}

/** Which apps used this app's data (builders only). */
export function useDeps(slug: string, enabled: boolean) {
  return useQuery({
    queryKey: ['apps', slug, 'deps'],
    queryFn: () => api.get<DependencyView[]>(`/api/deps/${encodeURIComponent(slug)}`),
    enabled,
  })
}

/** Where draft previews load the library mirror from; fixed for the server's lifetime. */
export function usePlatform() {
  return useQuery({ queryKey: ['platform'], queryFn: () => api.get<Platform>('/api/platform'), staleTime: Infinity })
}

export const conversationsKey = ['chat', 'conversations']

/** The caller's own build-chat conversations, most recently continued first. */
export function useConversations() {
  return useQuery({ queryKey: conversationsKey, queryFn: () => api.get<ConversationSummary[]>('/api/chat/conversations') })
}

/**
 * One conversation's messages, to open it. Read once: from then on the chat panel holds the live state, so the
 * cached copy is never refetched behind its back.
 */
export function useConversation(id: string | null) {
  return useQuery({
    queryKey: [...conversationsKey, id],
    queryFn: () => api.get<UIMessage[]>(`/api/chat/conversations/${id}`),
    enabled: id !== null,
    staleTime: Infinity,
    gcTime: 0,
  })
}

export function useGuestSignIn() {
  return useMutation({
    mutationFn: (request: GuestLoginRequest) =>
      api.post<GuestToken>('/api/auth/guest', request, { anonymous: true }),
    onSuccess: (session) => saveGuest(session),
  })
}
