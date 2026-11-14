import { useState } from 'react'
import { Link, useLocation, useParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { ApiError } from '../api/client'
import { jobsApi } from '../api/endpoints'
import { queryKeys } from '../api/queryKeys'
import type { Requirement } from '../api/types'
import { MustHaveBadge } from '../components/Badges'
import { EmptyState, ErrorBlock, LoadingBlock } from '../components/Feedback'
import { PageHeader } from '../components/PageHeader'
import { formatDate, formatSalaryRange } from '../lib/format'
import { REQUIREMENT_TYPE_LABEL, experienceLabel, humanize, jobTypeLabel } from '../lib/labels'
import { ApplyDialog } from './jobs/ApplyDialog'

export function JobDetailPage() {
  const { id } = useParams()
  const jobId = Number(id)
  const location = useLocation()
  const fromSearch = (location.state as { fromSearch?: string } | null)?.fromSearch ?? ''
  const [applying, setApplying] = useState(false)
  const job = useQuery({
    queryKey: queryKeys.job(jobId),
    queryFn: () => jobsApi.get(jobId),
    enabled: Number.isInteger(jobId) && jobId > 0,
  })

  const backLink = (
    <Link className="back-link" to={`/jobs${fromSearch}`}>
      ← All jobs
    </Link>
  )

  if (!Number.isInteger(jobId) || jobId <= 0 || (job.error instanceof ApiError && job.error.status === 404)) {
    return (
      <>
        {backLink}
        <PageHeader title="Job not available" />
        <EmptyState title="This job is not open any more">It may have closed or been removed. Browse the other open roles.</EmptyState>
      </>
    )
  }
  if (job.isPending) return <LoadingBlock label="Loading job…" />
  if (job.isError) return <ErrorBlock error={job.error} onRetry={() => void job.refetch()} />

  const data = job.data
  const mustHaves = data.requirements.filter(requirement => requirement.mandatory)
  const niceToHaves = data.requirements.filter(requirement => !requirement.mandatory)

  return (
    <>
      {backLink}
      <PageHeader
        eyebrow={data.organizationName ? data.organizationName.toUpperCase() : 'JOB'}
        title={data.title}
        description={
          <>
            {data.location} · {jobTypeLabel(data.jobType)} · {experienceLabel(data.experienceLevel)}
            {data.department ? ` · ${data.department}` : ''}
          </>
        }
        actions={
          <button type="button" className="primary" onClick={() => setApplying(true)}>
            Apply now <span aria-hidden="true">→</span>
          </button>
        }
      />

      <div className="detail-grid">
        <section className="panel" aria-labelledby="about-role">
          <h2 id="about-role" className="panel-title">
            About the role
          </h2>
          <div className="prose">{data.description}</div>
        </section>

        <aside className="panel facts-panel" aria-label="Key facts">
          <dl className="key-facts">
            <div>
              <dt>Organisation</dt>
              <dd>{data.organizationName || '—'}</dd>
            </div>
            <div>
              <dt>Salary</dt>
              <dd>{formatSalaryRange(data.minimumSalary, data.maximumSalary)}</dd>
            </div>
            <div>
              <dt>Openings</dt>
              <dd>{data.vacancies}</dd>
            </div>
            <div>
              <dt>Apply by</dt>
              <dd>{data.deadline ? formatDate(data.deadline) : 'No deadline'}</dd>
            </div>
            {data.publishedAt && (
              <div>
                <dt>Published</dt>
                <dd>{formatDate(data.publishedAt)}</dd>
              </div>
            )}
          </dl>
        </aside>
      </div>

      <section className="panel" aria-labelledby="requirements-title">
        <h2 id="requirements-title" className="panel-title">
          Requirements
        </h2>
        {data.requirements.length === 0 ? (
          <p className="muted">This employer has not listed structured requirements.</p>
        ) : (
          <>
            <p className="muted">
              Must-haves are what the employer checks first. A missing must-have does not reject you automatically; a
              person reviews every application.
            </p>
            {mustHaves.length > 0 && <RequirementList title="Must-haves" requirements={mustHaves} />}
            {niceToHaves.length > 0 && <RequirementList title="Nice to have" requirements={niceToHaves} />}
          </>
        )}
      </section>

      <div className="sticky-apply">
        <button type="button" className="primary" onClick={() => setApplying(true)}>
          Apply for this role
        </button>
      </div>

      <ApplyDialog job={data} open={applying} onClose={() => setApplying(false)} />
    </>
  )
}

function RequirementList({ title, requirements }: { title: string; requirements: Requirement[] }) {
  return (
    <>
      <h3 className="subheading">{title}</h3>
      <ul className="requirement-list">
        {requirements.map(requirement => {
          const meta = [
            requirement.minimumYears != null ? `${requirement.minimumYears}+ years` : null,
            requirement.proficiency ? `${humanize(requirement.proficiency)} level` : null,
            requirement.alternatives.length > 0 ? `Also accepted: ${requirement.alternatives.join(', ')}` : null,
          ]
            .filter(Boolean)
            .join(' · ')
          return (
            <li key={requirement.id}>
              <div className="requirement-head">
                <span className="fact-type-badge">{REQUIREMENT_TYPE_LABEL[requirement.type] ?? humanize(requirement.type)}</span>
                <MustHaveBadge mandatory={requirement.mandatory} />
              </div>
              <p className="requirement-text">{requirement.description}</p>
              {meta && <p className="muted requirement-meta">{meta}</p>}
            </li>
          )
        })}
      </ul>
    </>
  )
}
