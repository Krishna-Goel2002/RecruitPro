import { useMemo, useState } from 'react'
import { Link, useLocation, useParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { ApiError } from '../api/client'
import { jobsApi, resumesApi } from '../api/endpoints'
import { queryKeys } from '../api/queryKeys'
import type { Requirement } from '../api/types'
import { ErrorState, Facts } from '../components/Feedback'
import {
  Button, EmptyState, EvidenceLadder, LoadingRegion, PageHeader, ReqMarker, Skeleton, SkeletonText, buttonProps,
} from '../design/components'
import { ArrowRight, BriefcaseBusiness, CircleCheck, Icon } from '../design/icons'
import { formatDate, plural, relativeTime } from '../design/lib/format'
import { closesIn, formatSalaryRange } from '../lib/format'
import { REQUIREMENT_TYPE_LABEL, experienceLabel, humanize, jobTypeLabel } from '../lib/labels'
import { evidenceFrom, isCovered, matchRequirements, type RequirementMatch } from '../lib/match'
import { claimsQuery, latestResume, recentApplicationsQuery, resumesQuery } from '../lib/queries'
import { ApplyDialog } from './jobs/ApplyDialog'
import './common.css'
import './jobs/jobs.css'

/**
 * Job detail (REDESIGN_PLAN A3): a reading column (description, requirements with must-have markers, never red)
 * and a sticky rail with the key facts, Apply, and how the candidate's evidence lines up. Phones get a sticky
 * bottom bar with Apply.
 */
export function JobDetailPage() {
  const { id } = useParams()
  const jobId = Number(id)
  const valid = Number.isInteger(jobId) && jobId > 0
  const location = useLocation()
  const fromSearch = (location.state as { fromSearch?: string } | null)?.fromSearch ?? ''
  const [applying, setApplying] = useState(false)
  const job = useQuery({ queryKey: queryKeys.job(jobId), queryFn: () => jobsApi.get(jobId), enabled: valid })

  // Evidence for the match preview: the newest resume's facts and the candidate's claims.
  const resumes = useQuery(resumesQuery)
  const latest = latestResume(resumes.data)
  const analysis = useQuery({ queryKey: queryKeys.resumeAnalysis(latest?.id ?? 0), queryFn: () => resumesApi.analysis(latest!.id), enabled: !!latest })
  const claims = useQuery(claimsQuery)
  const applications = useQuery(recentApplicationsQuery)
  const existing = applications.data?.content.find(a => a.jobId === jobId && a.status !== 'WITHDRAWN') ?? null

  const matches = useMemo(
    () => (job.data ? matchRequirements(job.data.requirements, evidenceFrom(analysis.data?.facts, claims.data)) : []),
    [job.data, analysis.data, claims.data],
  )
  const haveEvidence = (analysis.data?.facts.length ?? 0) > 0 || (claims.data?.length ?? 0) > 0

  const crumbs = [{ label: 'Find jobs', to: `/jobs${fromSearch}` }]

  if (!valid || (job.error instanceof ApiError && job.error.status === 404)) {
    return (
      <>
        <PageHeader title="This job is not open any more" breadcrumb={[...crumbs, { label: 'Job' }]} />
        <EmptyState
          icon={BriefcaseBusiness}
          title="It may have closed or been removed"
          description="Applications you already sent stay in Applications. Plenty of other roles are open."
          action={
            <Link {...buttonProps('primary')} to={`/jobs${fromSearch}`}>
              See open roles
            </Link>
          }
        />
      </>
    )
  }
  if (job.isPending) return <JobSkeleton />
  if (job.isError) return <ErrorState error={job.error} onRetry={() => void job.refetch()} title="This job could not be loaded" />

  const data = job.data
  const mustHaves = data.requirements.filter(r => r.mandatory)
  const niceToHaves = data.requirements.filter(r => !r.mandatory)
  const matchFor = (r: Requirement) => matches.find(m => m.requirement.id === r.id)
  const salary = formatSalaryRange(data.minimumSalary, data.maximumSalary)

  const applyButton = existing ? (
    <Link {...buttonProps('secondary', 'lg')} data-block="" to={`/applications/${existing.id}`}>
      View your application
    </Link>
  ) : (
    <Button variant="primary" size="lg" block onClick={() => setApplying(true)}>
      Apply for this role
    </Button>
  )

  return (
    <>
      <PageHeader
        breadcrumb={[...crumbs, { label: data.title }]}
        title={data.title}
        meta={[
          data.organizationName ? <span className="j-org">{data.organizationName}</span> : null,
          data.location,
          jobTypeLabel(data.jobType),
          experienceLabel(data.experienceLevel),
          data.department || null,
        ]}
      />

      <div className="c-split">
        <div>
          <section className="jd-section" aria-labelledby="jd-about">
            <h2 id="jd-about" className="t-h2" style={{ marginBottom: 12 }}>
              About the role
            </h2>
            <div className="c-prose">{data.description}</div>
          </section>

          <section className="jd-section" aria-labelledby="jd-req">
            <h2 id="jd-req" className="t-h2" style={{ marginBottom: 8 }}>
              What they are looking for
            </h2>
            {data.requirements.length === 0 ? (
              <p className="c-muted">This employer has not listed structured requirements.</p>
            ) : (
              <>
                <p className="jd-req-intro">
                  Must-haves are what the employer checks first. A missing must-have does not reject you automatically; a person reviews every
                  application.
                </p>
                {mustHaves.length > 0 && <RequirementGroup title="Must-haves" items={mustHaves} matchFor={matchFor} showMatch={haveEvidence} />}
                {niceToHaves.length > 0 && <RequirementGroup title="Nice to have" items={niceToHaves} matchFor={matchFor} showMatch={haveEvidence} />}
              </>
            )}
          </section>
        </div>

        <aside className="c-rail" aria-label="Key facts and apply">
          <div className="c-rail-block jd-apply-block">
            <Facts
              items={[
                { label: 'Salary', value: <span className="numeric">{salary}</span> },
                { label: 'Openings', value: data.vacancies },
                {
                  label: 'Apply by',
                  value: data.deadline ? (
                    <span>
                      {formatDate(data.deadline)} <span className="c-meta">· {closesIn(data.deadline).replace('Closes ', '')}</span>
                    </span>
                  ) : (
                    'No closing date'
                  ),
                },
                ...(data.publishedAt ? [{ label: 'Published', value: formatDate(data.publishedAt) }] : []),
              ]}
            />
            <div className="jd-rail-apply jd-apply-block">
              {existing && (
                <p className="jd-applied">
                  <Icon icon={CircleCheck} size={16} /> You applied {relativeTime(existing.appliedAt)}
                </p>
              )}
              {applyButton}
            </div>
          </div>
          {data.requirements.length > 0 && (
            <MatchBlock matches={matches} haveEvidence={haveEvidence} loading={resumes.isPending || claims.isPending || (!!latest && analysis.isPending)} />
          )}
        </aside>
      </div>

      <div className="jd-mobile-bar">
        <span className="jd-mobile-salary numeric">{existing ? 'You applied' : salary}</span>
        {existing ? (
          <Link {...buttonProps('secondary')} to={`/applications/${existing.id}`}>
            View application
          </Link>
        ) : (
          <Button variant="primary" onClick={() => setApplying(true)}>
            Apply
          </Button>
        )}
      </div>

      <ApplyDialog job={data} matches={haveEvidence ? matches : []} open={applying} onClose={() => setApplying(false)} />
    </>
  )
}

function RequirementGroup({
  title, items, matchFor, showMatch,
}: { title: string; items: Requirement[]; matchFor: (r: Requirement) => RequirementMatch | undefined; showMatch: boolean }) {
  return (
    <div className="jd-req-group">
      <h3 className="jd-req-group-title">
        {title} <span className="numeric">· {items.length}</span>
      </h3>
      <ul className="c-rows" data-closed="">
        {items.map(r => {
          const m = matchFor(r)
          return (
            <li key={r.id} className="jd-req">
              <span className="jd-req-text">{r.description}</span>
              <span className="jd-req-meta">
                <span className="jd-req-type">{REQUIREMENT_TYPE_LABEL[r.type] ?? humanize(r.type)}</span>
                <ReqMarker kind={r.mandatory ? 'must' : 'nice'} />
                {r.mandatory && r.knockout && <ReqMarker kind="gate">Checked first</ReqMarker>}
                {r.minimumYears != null && <span className="c-meta">{r.minimumYears}+ years</span>}
                {r.proficiency && <span className="c-meta">{humanize(r.proficiency)} level</span>}
                {r.alternatives.length > 0 && <span className="c-meta">Also accepted: {r.alternatives.join(', ')}</span>}
              </span>
              {showMatch && m && (
                <span className="jd-req-match">
                  <EvidenceLadder tier={m.tier} audience="candidate" />
                </span>
              )}
            </li>
          )
        })}
      </ul>
    </div>
  )
}

function MatchBlock({ matches, haveEvidence, loading }: { matches: RequirementMatch[]; haveEvidence: boolean; loading: boolean }) {
  if (loading) {
    return (
      <div className="c-rail-block" aria-hidden="true">
        <Skeleton width={180} height={12} />
        <Skeleton width="100%" height={6} style={{ marginTop: 14 }} />
      </div>
    )
  }
  if (!haveEvidence) {
    return (
      <div className="c-rail-block">
        <p className="c-rail-title">How your evidence matches</p>
        <p className="c-muted">Add your resume to see which of these requirements it already covers before you apply.</p>
        <Link {...buttonProps('secondary', 'sm')} to="/resumes" style={{ marginTop: 12 }}>
          Add your resume
        </Link>
      </div>
    )
  }
  const covered = matches.filter(isCovered).length
  const weakMust = matches.some(m => m.requirement.mandatory && !isCovered(m))
  return (
    <div className="c-rail-block">
      <div className="jd-match-head">
        <p className="c-rail-title" style={{ margin: 0 }}>
          How your evidence matches
        </p>
      </div>
      <p className="c-muted">
        <span className="jd-match-count numeric">
          {covered} of {matches.length}
        </span>{' '}
        requirements found in your resume and claims
      </p>
      <div className="jd-match-bar" aria-hidden="true">
        {matches.map((m, i) => (
          <span key={m.requirement.id} style={{ ['--i' as string]: i }} data-on={isCovered(m) || undefined} data-claim={m.tier === 'SELF_CLAIMED' || undefined} />
        ))}
      </div>
      <ul className="jd-match-list">
        {matches.map(m => (
          <li key={m.requirement.id}>
            <span title={m.requirement.description}>{m.requirement.description}</span>
            <EvidenceLadder tier={m.tier} audience="candidate" animate={false} />
          </li>
        ))}
      </ul>
      {weakMust && (
        <Link {...buttonProps('secondary', 'sm')} to="/verification?add=source&step=choose" style={{ marginBottom: 12 }}>
          Strengthen your evidence
          <Icon icon={ArrowRight} size={14} />
        </Link>
      )}
      <p className="jd-match-note">
        A quick text match for you only. The employer runs its own matching after you apply, and a person reviews every application. {plural(matches.length, 'requirement')} listed.
      </p>
    </div>
  )
}

function JobSkeleton() {
  return (
    <LoadingRegion label="Loading job">
      <Skeleton width={160} height={10} />
      <Skeleton width={320} height={24} style={{ marginTop: 12 }} />
      <Skeleton width={260} height={10} style={{ marginTop: 10 }} />
      <div className="c-split" style={{ marginTop: 32 }}>
        <div>
          <Skeleton width={140} height={14} />
          <div style={{ marginTop: 16 }}>
            <SkeletonText lines={5} />
          </div>
        </div>
        <div>
          <SkeletonText lines={4} />
          <Skeleton width="100%" height={40} style={{ marginTop: 16 }} />
        </div>
      </div>
    </LoadingRegion>
  )
}
