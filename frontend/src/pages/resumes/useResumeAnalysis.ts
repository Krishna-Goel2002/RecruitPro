import { useCallback, useEffect, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { resumesApi } from '../../api/endpoints'
import { queryKeys } from '../../api/queryKeys'
import type { ResumeAnalysisStatus } from '../../api/types'

export const POLL_INTERVAL_MS = 3000
/** Stop polling after about two minutes; the candidate can check again manually (audit L8). */
export const POLL_LIMIT_MS = 120_000

export function isInProgress(status: ResumeAnalysisStatus | undefined): boolean {
  return status === 'PENDING' || status === 'PROCESSING'
}

/**
 * Loads a resume's analysis and polls only while it is PENDING/PROCESSING, for at most POLL_LIMIT_MS.
 * `checkAgain()` refetches and restarts the polling window (manual refresh, retry, new upload).
 */
export function useResumeAnalysis(resumeId: number) {
  const [pollingSince, setPollingSince] = useState(() => Date.now())
  const [stalled, setStalled] = useState(false)

  const query = useQuery({
    queryKey: queryKeys.resumeAnalysis(resumeId),
    queryFn: () => resumesApi.analysis(resumeId),
    refetchInterval: current => (!stalled && isInProgress(current.state.data?.status) ? POLL_INTERVAL_MS : false),
    refetchIntervalInBackground: false,
  })

  const inProgress = isInProgress(query.data?.status)

  useEffect(() => {
    if (!inProgress || stalled) return
    const remaining = POLL_LIMIT_MS - (Date.now() - pollingSince)
    const timer = window.setTimeout(() => setStalled(true), Math.max(0, remaining))
    return () => window.clearTimeout(timer)
  }, [inProgress, stalled, pollingSince])

  const { refetch } = query
  const checkAgain = useCallback(() => {
    setStalled(false)
    setPollingSince(Date.now())
    void refetch()
  }, [refetch])

  return { query, inProgress, stalled: inProgress && stalled, checkAgain }
}
