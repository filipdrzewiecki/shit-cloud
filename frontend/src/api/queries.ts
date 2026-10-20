import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { saveGuest } from '../auth/guestSession'
import { api } from './client'
import type {
  AppDetail,
  AppSummary,
  AppUsage,
  ChatMessage,
  ChatResponse,
  DeleteResult,
  DeployRequest,
  GuestLoginRequest,
  GuestToken,
  Me,
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

export function useReplaceHtml(slug: string) {
  return useAppMutation((html: string) => api.put<AppSummary>(`/api/apps/${slug}/html`, { html }))
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

export function useChat() {
  return useMutation({
    mutationFn: (messages: ChatMessage[]) => api.post<ChatResponse>('/api/chat/mock', { messages }),
  })
}

export function useGuestSignIn() {
  return useMutation({
    mutationFn: (request: GuestLoginRequest) =>
      api.post<GuestToken>('/api/auth/guest', request, { anonymous: true }),
    onSuccess: (session) => saveGuest(session),
  })
}
