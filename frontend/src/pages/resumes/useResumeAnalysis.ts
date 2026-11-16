import { useCallback, useRef } from 'react'
import { useQuery } from '@tanstack/react-query'
import { resumesApi } from '../../api/endpoints'
import { queryKeys } from '../../api/queryKeys'
import type { ResumeAnalysisStatus } from '../../api/types'
import { POLL_LIMIT_MS, usePollingWindow } from '../../lib/polling'

export const POLL_INTERVAL_MS = 3000
/** Stop polling after about two minutes; the candidate can check again manually (audit L8). */
export { POLL_LIMIT_MS }

export function isInProgress(status: ResumeAnalysisStatus | undefined): boolean {
  return status === 'PENDING' || status === 'PROCESSING'
}

/**
 * Loads a resume's analysis and polls only while it is PENDING/PROCESSING, for at most POLL_LIMIT_MS.
 * `checkAgain()` refetches and restarts the polling window (manual refresh, retry, new upload).
 */
export function useResumeAnalysis(resumeId: number) {
  // `active` is known only after the first response; the window effect reads it from the query result below.
  // React Query may call refetchInterval while useQuery runs, before `polling` exists, so it reads a ref.
  const pollingRef = useRef<ReturnType<typeof usePollingWindow> | null>(null)
  const query = useQuery({
    queryKey: queryKeys.resumeAnalysis(resumeId),
    queryFn: () => resumesApi.analysis(resumeId),
    refetchInterval: current => {
      const active = isInProgress(current.state.data?.status)
      const fetches = current.state.dataUpdateCount + current.state.errorUpdateCount
      return pollingRef.current ? pollingRef.current.nextInterval(active, fetches) : active ? POLL_INTERVAL_MS : false
    },
    refetchIntervalInBackground: false,
  })

  const inProgress = isInProgress(query.data?.status)
  const polling = usePollingWindow({ active: inProgress, intervalMs: POLL_INTERVAL_MS, limitMs: POLL_LIMIT_MS })
  pollingRef.current = polling

  const { refetch } = query
  const { restart } = polling
  const checkAgain = useCallback(() => {
    restart()
    void refetch()
  }, [restart, refetch])

  return { query, inProgress, stalled: polling.stalled, checkAgain }
}
