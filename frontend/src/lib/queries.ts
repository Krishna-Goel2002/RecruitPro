/**
 * Shared query options for data several screens read (Overview, navigation counts, job match, next steps).
 * One definition per resource keeps keys, fetchers and freshness identical everywhere.
 */
import { queryOptions } from '@tanstack/react-query'
import {
  applicationsApi,
  claimsApi,
  interviewsApi,
  resumesApi,
  verificationApi,
  workSamplesApi,
} from '../api/endpoints'
import { queryKeys } from '../api/queryKeys'
import type { Resume } from '../api/types'

export const resumesQuery = queryOptions({ queryKey: queryKeys.resumes, queryFn: resumesApi.list })
export const claimsQuery = queryOptions({ queryKey: queryKeys.claims, queryFn: claimsApi.list })
export const ledgerQuery = queryOptions({ queryKey: queryKeys.verificationLedger, queryFn: verificationApi.ledger })
export const sourcesQuery = queryOptions({ queryKey: queryKeys.verificationSources, queryFn: verificationApi.sources })
export const referencesQuery = queryOptions({ queryKey: queryKeys.references, queryFn: verificationApi.references })
export const workSamplesQuery = queryOptions({ queryKey: queryKeys.workSamples, queryFn: workSamplesApi.list })
export const interviewsQuery = queryOptions({ queryKey: queryKeys.interviews, queryFn: interviewsApi.list })

/** Up to 100 recent applications, for "Applied" markers, next steps and notification links (one call). */
export const recentApplicationsQuery = queryOptions({
  queryKey: ['applications', 'recent'] as const,
  queryFn: () => applicationsApi.list(0, 100),
  staleTime: 60_000,
})

export function newestFirst<T extends { uploadedAt: string }>(items: T[]): T[] {
  return [...items].sort((a, b) => b.uploadedAt.localeCompare(a.uploadedAt))
}

export function latestResume(resumes: Resume[] | undefined): Resume | null {
  return resumes && resumes.length ? newestFirst(resumes)[0] : null
}
