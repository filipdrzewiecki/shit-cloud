import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { UIMessage } from 'ai'
import { saveGuest } from '../auth/guestSession'
import { api } from './client'
import type {
  AppDetail,
  AppSummary,
  AppUsage,
  CollectionSummary,
  ConversationSummary,
  DeleteResult,
  DependencyView,
  DeployRequest,
  DocHistory,
  DocPage,
  DocView,
  GuestLoginRequest,
  GuestToken,
  Me,
  MigrationUndo,
  MigrationView,
  Platform,
  Upload,
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

const dataKey = (slug: string) => ['apps', slug, 'data']

/** The app's collections with how many documents each holds now and how many were removed (builders only). */
export function useDataCollections(slug: string) {
  return useQuery({
    queryKey: dataKey(slug),
    queryFn: () => api.get<CollectionSummary[]>(`/api/apps/${encodeURIComponent(slug)}/data`),
  })
}

const PAGE = 50

/** A collection's documents, page by page ("Load more"); `removed` lists the ones gone. */
export function useDocuments(slug: string, collection: string | null, removed: boolean, prefix: string) {
  return useInfiniteQuery({
    queryKey: [...dataKey(slug), collection, removed, prefix],
    queryFn: ({ pageParam }) => {
      const params = new URLSearchParams({ removed: String(removed), offset: String(pageParam), limit: String(PAGE) })
      if (prefix) params.set('prefix', prefix)
      return api.get<DocPage>(
        `/api/apps/${encodeURIComponent(slug)}/data/${encodeURIComponent(collection ?? '')}?${params}`,
      )
    },
    initialPageParam: 0,
    getNextPageParam: (last, pages) => (last.hasMore ? pages.length * PAGE : undefined),
    enabled: collection !== null,
  })
}

/** One document now and its earlier states. */
export function useDocHistory(slug: string, collection: string | null, id: string | null) {
  return useQuery({
    queryKey: [...dataKey(slug), collection, 'history', id],
    queryFn: () =>
      api.get<DocHistory>(
        `/api/apps/${encodeURIComponent(slug)}/data/${encodeURIComponent(collection ?? '')}/${encodeURIComponent(id ?? '')}/history`,
      ),
    enabled: collection !== null && id !== null,
  })
}

/** Puts an earlier state back; refused (409) when the document changed since `expectedVersion`. */
export function useRestoreVersion(slug: string, collection: string, id: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ historyId, expectedVersion }: { historyId: number; expectedVersion: number }) =>
      api.post<DocView>(
        `/api/apps/${encodeURIComponent(slug)}/data/${encodeURIComponent(collection)}/${encodeURIComponent(id)}/restore`,
        { historyId, expectedVersion },
      ),
    // Also after a refusal: the page must show the document as it is now.
    onSettled: () => queryClient.invalidateQueries({ queryKey: dataKey(slug) }),
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

/** The original file of an import conversation, for its "Before" view; it never changes. */
export function useUpload(conversationId: string | null, enabled: boolean) {
  return useQuery({
    queryKey: [...conversationsKey, conversationId, 'upload'],
    queryFn: () => api.get<Upload>(`/api/chat/conversations/${conversationId}/upload`),
    enabled: enabled && conversationId !== null,
    staleTime: Infinity,
  })
}

export function useGuestSignIn() {
  return useMutation({
    mutationFn: (request: GuestLoginRequest) =>
      api.post<GuestToken>('/api/auth/guest', request, { anonymous: true }),
    onSuccess: (session) => saveGuest(session),
  })
}
