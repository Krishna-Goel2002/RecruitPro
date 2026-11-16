import { Link } from 'react-router-dom'
import type { JobStaff, Requirement } from '../../api/types'
import { useAuth } from '../../auth/AuthContext'
import { EmptyState, ReqMarker, Section, buttonProps } from '../../design/components'
import { formatDate, formatMoney, plural } from '../../design/lib/format'
import { ListChecks } from '../../design/icons'
import { formatDateTime, humanize } from '../../lib/format'
import { stripRequirements } from './ranking'

export function salaryText(min: number | null, max: number | null): string {
  if (min == null && max == null) return 'Not stated'
  if (min != null && max != null) return `${formatMoney(min)} – ${formatMoney(max)} a year`
  return min != null ? `From ${formatMoney(min)} a year` : `Up to ${formatMoney(max as number)} a year`
}

export function RequirementRow({ requirement }: { requirement: Requirement }) {
  const r = requirement
  const extras = [
    humanize(r.type),
    r.minimumYears != null ? `${plural(r.minimumYears, 'year')} minimum` : null,
    r.proficiency ? humanize(r.proficiency) : null,
  ].filter(Boolean)
  return (
    <li className="hra-req-view">
      <div className="hra-req-view-main">
        <span className="hra-req-view-text">{r.description}</span>
        {r.alternatives?.length > 0 && <span className="hra-req-view-alt">Also accepted: {r.alternatives.join(', ')}</span>}
      </div>
      <div className="hra-req-view-tags">
        <ReqMarker kind={r.mandatory ? 'must' : 'nice'} />
        {r.mandatory && r.knockout && <ReqMarker kind="gate" />}
        <span className="hra-req-view-extra">{extras.join(' · ')}</span>
      </div>
    </li>
  )
}

/** Details tab: the description at reading width, the requirements in ranking order, and the role facts. */
export function JobDetailsTab({ job }: { job: JobStaff }) {
  const { can } = useAuth()
  const ordered = [...stripRequirements(job.requirements), ...job.requirements.filter(r => r.type === 'OTHER')]
  return (
    <div className="hra-details">
      <div className="hra-details-main">
        <Section title="About the role">
          <p className="hra-prose">{job.description}</p>
        </Section>
        <Section
          title="Requirements"
          description={job.requirements.length ? 'In ranking order: gates first, then must-haves, then nice-to-haves. Must-haves count double in coverage.' : undefined}
          actions={can('jobs.manage') && job.status !== 'CLOSED' && job.status !== 'CANCELLED'
            ? <Link {...buttonProps('ghost', 'sm')} to={`/jobs/${job.id}/edit?step=requirements`}>Edit requirements</Link>
            : undefined}
        >
          {ordered.length === 0 ? (
            <EmptyState compact icon={ListChecks} title="No requirements yet" description="Applicants cannot be ranked until requirements exist." />
          ) : (
            <ul className="hra-req-view-list">{ordered.map(r => <RequirementRow key={r.id} requirement={r} />)}</ul>
          )}
        </Section>
      </div>
      <aside className="hra-details-facts" aria-labelledby="facts-h">
        <h2 id="facts-h" className="t-h3">Role facts</h2>
        <dl>
          <dt>Employment type</dt><dd>{humanize(job.jobType)}</dd>
          <dt>Experience level</dt><dd>{humanize(job.experienceLevel)}</dd>
          <dt>Salary</dt><dd>{salaryText(job.minimumSalary ?? null, job.maximumSalary ?? null)}</dd>
          <dt>Openings</dt><dd className="numeric">{job.vacancies}</dd>
          <dt>Deadline</dt><dd>{job.deadline ? formatDateTime(job.deadline) : 'None'}</dd>
          <dt>Published</dt><dd>{job.publishedAt ? formatDate(job.publishedAt) : 'Not yet'}</dd>
          <dt>Created by</dt><dd>{job.createdByName || job.createdByEmail || 'A former team member'}</dd>
          <dt>Last updated</dt><dd>{formatDateTime(job.updatedAt)}</dd>
          {job.requirementsVersion != null && <><dt>Requirements</dt><dd>Version {job.requirementsVersion}</dd></>}
        </dl>
      </aside>
    </div>
  )
}
