import { useQueryClient } from '@tanstack/react-query'
import { type RefObject, useEffect } from 'react'
import { dataApi } from '../api/data'
import { meQuery } from '../api/queries'
import { acceptRequest, type BridgeBackend, handleRequest } from './appBridge'

/**
 * Answers the SDK calls of the app shown in `frameRef` (see appBridge.ts for
 * the rules). `space` must be the slug of that app, from the route.
 */
export function useAppBridge(frameRef: RefObject<HTMLIFrameElement>, frameUrl: string, space: string) {
  const queryClient = useQueryClient()

  useEffect(() => {
    const appOrigin = new URL(frameUrl).origin
    const backend: BridgeBackend = {
      me: () => queryClient.ensureQueryData(meQuery),
      query: dataApi.query,
      set: dataApi.set,
      add: dataApi.add,
      remove: dataApi.remove,
    }

    async function onMessage(event: MessageEvent) {
      const frame = frameRef.current?.contentWindow ?? null
      const request = acceptRequest(event, frame, appOrigin)
      if (!request || !frame) return
      const reply = await handleRequest(request, space, backend)
      frame.postMessage(reply, appOrigin)
    }

    window.addEventListener('message', onMessage)
    return () => window.removeEventListener('message', onMessage)
  }, [frameRef, frameUrl, space, queryClient])
}
