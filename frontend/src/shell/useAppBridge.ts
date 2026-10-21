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
      // Every call names this app, so the server can note which app used which data.
      query: (target, collection, query) => dataApi.query(target, collection, query, space),
      set: (target, collection, id, data, expectedVersion) =>
        dataApi.set(target, collection, id, data, expectedVersion, space),
      add: (target, collection, data) => dataApi.add(target, collection, data, space),
      remove: (target, collection, id, expectedVersion) => dataApi.remove(target, collection, id, expectedVersion, space),
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
