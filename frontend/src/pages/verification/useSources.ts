import { useCallback, useEffect, useRef } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { verificationApi } from '../../api/endpoints'
import { queryKeys } from '../../api/queryKeys'
import type { VerificationSource } from '../../api/types'
import { usePollingWindow } from '../../lib/polling'
import { isRunActive } from '../../lib/verification'

const POLL_INTERVAL_MS = 3000
const POLL_MAX_INTERVAL_MS = 15_000

function anyRunActive(sources: VerificationSource[] | undefined): boolean {
  return !!sources?.some(source => isRunActive(source.lastRun?.status))
}

/**
 * Connected sources, polled (3 s × 1.3 backoff, at most 15 s, for 2 minutes) while any run is PENDING/RUNNING.
 * When the last run finishes the Claim Ledger is refetched and `onFinished` fires once.
 */
export function useSources(onFinished?: () => void) {
  const queryClient = useQueryClient()
  // React Query may call refetchInterval while useQuery runs, before `polling` exists, so it reads a ref.
  const pollingRef = useRef<ReturnType<typeof usePollingWindow> | null>(null)
  const query = useQuery({
    queryKey: queryKeys.verificationSources,
    queryFn: verificationApi.sources,
    refetchInterval: q => {
      const running = anyRunActive(q.state.data)
      return pollingRef.current ? pollingRef.current.nextInterval(running) : running ? POLL_INTERVAL_MS : false
    },
    refetchIntervalInBackground: false,
  })
  const active = anyRunActive(query.data)
  const polling = usePollingWindow({ active, intervalMs: POLL_INTERVAL_MS, backoff: 1.3, maxIntervalMs: POLL_MAX_INTERVAL_MS })
  pollingRef.current = polling

  const finishedRef = useRef(onFinished)
  finishedRef.current = onFinished
  const wasActive = useRef(active)
  useEffect(() => {
    if (wasActive.current && !active) {
      void queryClient.invalidateQueries({ queryKey: queryKeys.verificationLedger }).then(() => finishedRef.current?.())
    }
    wasActive.current = active
  }, [active, queryClient])

  const { restart } = polling
  const { refetch } = query
  const checkAgain = useCallback(() => {
    restart()
    void refetch()
  }, [restart, refetch])

  const startRun = useMutation({
    mutationFn: (sourceId: number) => verificationApi.startRun(sourceId),
    onSuccess: (run, sourceId) => {
      queryClient.setQueryData<VerificationSource[]>(queryKeys.verificationSources, current =>
        current?.map(source =>
          source.id === sourceId ? { ...source, lastRun: { id: run.id, status: run.status, finishedAt: run.finishedAt, errorCode: run.errorCode } } : source,
        ),
      )
      restart()
      void queryClient.invalidateQueries({ queryKey: queryKeys.verificationSources })
    },
  })

  return { query, active, stalled: polling.stalled, checkAgain, startRun, restart }
}

export type SourcesState = ReturnType<typeof useSources>
