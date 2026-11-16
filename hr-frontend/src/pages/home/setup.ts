import { useEffect, useState } from 'react'
import { useQueries, useQuery } from '@tanstack/react-query'
import { accountApi } from '../../api/account'
import { adminApi } from '../../api/admin'
import { jobsApi } from '../../api/jobs'
import { qk } from '../../api/queryKeys'
import { useCurrentUser } from '../../auth/AuthContext'
import { readFlag, writeFlag } from '../../lib/lastSeen'

export const SETUP_HIDDEN_FLAG = 'hr-setup-hidden'
const ORG_REVIEWED_FLAG = 'hr-setup-org-reviewed'

export type StepState = 'done' | 'open' | 'loading'

/**
 * Data behind the first-run checklist. Each step is detected from real data, so it ticks itself when done elsewhere.
 * Shared by the Get started page and the sidebar count.
 */
export function useSetupProgress(enabled: boolean) {
  const user = useCurrentUser()
  const mfa = useQuery({ queryKey: qk.mfaStatus, queryFn: ({ signal }) => accountApi.mfaStatus(signal), enabled, staleTime: 60_000, retry: false })
  const [invites, users, org, jobs, published] = useQueries({
    queries: [
      { queryKey: qk.adminInvitations, queryFn: ({ signal }: { signal: AbortSignal }) => adminApi.invitations(signal), enabled, staleTime: 60_000 },
      { queryKey: qk.adminUsers({ page: 0, size: 1 }), queryFn: ({ signal }: { signal: AbortSignal }) => adminApi.users({ page: 0, size: 1 }, signal), enabled, staleTime: 60_000 },
      { queryKey: qk.adminOrganization, queryFn: ({ signal }: { signal: AbortSignal }) => adminApi.organization(signal), enabled, staleTime: 60_000 },
      { queryKey: qk.jobStats('ALL'), queryFn: ({ signal }: { signal: AbortSignal }) => jobsApi.list({ page: 0, size: 1 }, signal), enabled, staleTime: 60_000 },
      { queryKey: qk.jobStats('PUBLISHED'), queryFn: ({ signal }: { signal: AbortSignal }) => jobsApi.list({ page: 0, size: 1, status: 'PUBLISHED' }, signal), enabled, staleTime: 60_000 },
    ],
  })
  const [orgReviewed, setOrgReviewed] = useState(() => readFlag(ORG_REVIEWED_FLAG))
  useEffect(() => {
    const sync = () => setOrgReviewed(readFlag(ORG_REVIEWED_FLAG))
    window.addEventListener('hr-setup-change', sync)
    return () => window.removeEventListener('hr-setup-change', sync)
  }, [])
  const state = (loading: boolean, done: boolean): StepState => (loading ? 'loading' : done ? 'done' : 'open')
  const status = {
    email: state(false, user.emailVerified),
    mfa: state(mfa.isPending, !!mfa.data?.enabled),
    org: state(false, orgReviewed),
    team: state(invites.isPending || users.isPending, (invites.data?.length ?? 0) > 0 || (users.data?.totalElements ?? 0) > 1),
    job: state(jobs.isPending, (jobs.data?.totalElements ?? 0) > 0),
    publish: state(published.isPending, (published.data?.totalElements ?? 0) > 0),
  }
  const open = Object.values(status).filter(s => s !== 'done').length
  return {
    status,
    open,
    org: org.data,
    mfaUnavailable: mfa.data?.available === false,
    markOrgReviewed: () => { writeFlag(ORG_REVIEWED_FLAG, true); setOrgReviewed(true); window.dispatchEvent(new Event('hr-setup-change')) },
  }
}

