import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from './client'
import type { AppDetail, AppSummary, ChatMessage, ChatResponse, DeployRequest, Me } from './types'

export function useMe() {
  return useQuery({ queryKey: ['me'], queryFn: () => api.get<Me>('/api/me'), staleTime: Infinity })
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

export function useChat() {
  return useMutation({
    mutationFn: (messages: ChatMessage[]) => api.post<ChatResponse>('/api/chat', { messages }),
  })
}
