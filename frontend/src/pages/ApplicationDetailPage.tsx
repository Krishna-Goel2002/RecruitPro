import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ApiError } from '../api/client'
import { applicationsApi } from '../api/endpoints'
import { queryKeys } from '../api/queryKeys'
import type { ApplicationDetail, ApplicationRequirement, ApplicationStatus } from '../api/types'
import { ClaimForm } from '../components/ClaimForm'
import { ConfirmDialog } from '../components/Dialog'
import { EnterList } from '../components/EnterList'
import { ErrorState, Facts } from '../components/Feedback'
import { useNotify } from '../components/Notices'
import {
  Badge, Button, CountUp, Drawer, EmptyState, IconButton, LoadingRegion, Menu, PageHeader, Panel, ReqMarker, Skeleton, SkeletonText, Timeline,
  buttonProps, type TimelineItem,
} from '../design/components'
import {
  ArrowRight, ArrowUpRight, BadgeCheck, Calendar, CalendarClock, Clock, Ellipsis, FileText, Icon, ListChecks, Plus, X,
} from '../design/icons'
import { formatDate, relativeTime } from '../design/lib/format'
import { downloadInterviewIcs, timeRange, timeZoneLabel } from '../lib/calendar'
import { focusPageHeading } from '../lib/focus'
import {
  APPLICATION_STATUS_DESCRIPTION, REQUIREMENT_TYPE_LABEL, WITHDRAWABLE_STATUSES, applicationStatusLabel, claimTypeFor, humanize, workSampleStatusLabel,
} from '../lib/labels'
import { interviewEnd, nextStepFor } from '../lib/nextStep'
import { isHttpUrl } from '../lib/url'
import { ApplicationStatusBadge } from './ApplicationsPage'
import './common.css'
import './applications/applications.css'

export function ApplicationDetailPage() {
  const { id } = useParams()
  const applicationId = Number(id)
  const valid = Number.isInteger(applicationId) && applicationId > 0
  const application = useQuery({ queryKey: queryKeys.application(applicationId), queryFn: () => applicationsApi.get(applicationId), enabled: valid })

  if (!valid || (application.error instanceof ApiError && application.error.status === 404)) {
    return (
      <>
        <PageHeader title="Application not found" breadcrumb={[{ label: 'Applications', to: '/applications' }, { label: 'Not found' }]} />
        <EmptyState
          icon={FileText}
          title="We could not find this application"
          description="It may belong to another account, or the link is wrong."
          action={
            <Link {...buttonProps('secondary')} to="/applications">
              All applications
            </Link>
          }
        />
      </>
    )
  }
  if (application.isPending) return <DetailSkeleton />
  if (application.isError) return <ErrorState error={application.error} onRetry={() => void application.refetch()} title="This application could not be loaded" />
  return <ApplicationView detail={application.data} />
}

const PATH: ApplicationStatus[] = ['SUBMITTED', 'SHORTLISTED', 'INTERVIEWING', 'SELECTED']
const FUTURE_TITLE: Partial<Record<ApplicationStatus, string>> = { SHORTLISTED: 'Shortlist', INTERVIEWING: 'Interview', SELECTED: 'Decision' }

/** Past steps from the server's timeline, then the steps still ahead, so "what's next" is always visible. */
function timelineItems(detail: ApplicationDetail): TimelineItem[] {
  const past: TimelineItem[] = detail.timeline.map((entry, i) => ({
    id: `p${i}`,
    title: applicationStatusLabel(entry.status),
    meta: APPLICATION_STATUS_DESCRIPTION[entry.status],
    at: entry.at,
    state: i === detail.timeline.length - 1 ? 'current' : 'done',
  }))
  const status = detail.status
  if (status === 'REJECTED' || status === 'WITHDRAWN' || status === 'SELECTED') {
    return past.map((p, i) => (i === past.length - 1 ? { ...p, state: 'done' } : p))
  }
  const reached = PATH.indexOf(status)
  const future = PATH.slice(reached + 1).map(s => ({ id: `f${s}`, title: FUTURE_TITLE[s] ?? applicationStatusLabel(s), state: 'future' as const }))
  return [...past, ...future]
}

function ApplicationView({ detail }: { detail: ApplicationDetail }) {
  const queryClient = useQueryClient()
  const notify = useNotify()
  const [confirmWithdraw, setConfirmWithdraw] = useState(false)
  const [evidenceFor, setEvidenceFor] = useState<ApplicationRequirement | null>(null)
  const [evidenceDirty, setEvidenceDirty] = useState(false)
  const [confirmDiscard, setConfirmDiscard] = useState(false)
  const closeEvidence = () => (evidenceDirty ? setConfirmDiscard(true) : setEvidenceFor(null))
  const canWithdraw = WITHDRAWABLE_STATUSES.includes(detail.status)
  const withdraw = useMutation({
    mutationFn: () => applicationsApi.withdraw(detail.id),
    onSuccess: () => {
      setConfirmWithdraw(false)
      notify('success', 'Application withdrawn', { body: `The employer will stop considering you for ${detail.jobTitle}.` })
      void queryClient.invalidateQueries({ queryKey: queryKeys.applicationsAll })
      // Withdrawing closes the application's open tasks too: the Overview, nav counts and task pages must not keep them.
      void queryClient.invalidateQueries({ queryKey: queryKeys.workSamples })
      void queryClient.invalidateQueries({ queryKey: queryKeys.interviews })
      void queryClient.invalidateQueries({ queryKey: queryKeys.application(detail.id) }).then(focusPageHeading)
    },
  })

  return (
    <>
      <PageHeader
        breadcrumb={[{ label: 'Applications', to: '/applications' }, { label: detail.jobTitle }]}
        title={detail.jobTitle}
        documentTitle={`Application: ${detail.jobTitle}`}
        status={<ApplicationStatusBadge status={detail.status} />}
        meta={[detail.organizationName || null, <>Applied {formatDate(detail.appliedAt, { dateStyle: 'medium' })}</>]}
        actions={
          <>
            <Link {...buttonProps('secondary')} to={`/jobs/${detail.jobId}`}>
              View job
            </Link>
            {canWithdraw && (
              <Menu
                label="More actions"
                items={[{ label: 'Withdraw application…', icon: X, danger: true, onSelect: () => setConfirmWithdraw(true) }]}
                trigger={props => <IconButton {...props} label="More actions" icon={Ellipsis} variant="secondary" />}
              />
            )}
          </>
        }
      />

      <NextStepPanel detail={detail} />

      <div className="c-split a-split">
        <div>
          <section className="a-section" aria-labelledby="a-progress">
            <h2 id="a-progress" className="t-h2">
              Progress
            </h2>
            <Timeline items={timelineItems(detail)} label="Application progress" />
          </section>

          <section className="a-section" aria-labelledby="a-req">
            <h2 id="a-req" className="t-h2">
              Requirements and your evidence
            </h2>
            <p className="c-muted a-section-desc">
              Verified means the employer has evidence: your resume, a recruiter check of a claim, a work sample or an interview.
            </p>
            {detail.requirements === null ? (
              <p className="c-muted">
                {canWithdraw ? 'This employer does not share verification status with candidates.' : 'Verification status is shown while an application is active.'}
              </p>
            ) : detail.requirements.length === 0 ? (
              <p className="c-muted">This job has no structured requirements.</p>
            ) : (
              <>
              <RequirementSummary requirements={detail.requirements} />
              <EnterList className="c-rows" data-closed="">
                {detail.requirements.map(r => (
                  <li key={r.requirementId} className="a-req">
                    <div className="a-req-main">
                      <span className="t-strong">{r.description}</span>
                      <span className="a-req-meta">
                        <span className="c-meta">{REQUIREMENT_TYPE_LABEL[r.type] ?? humanize(r.type)}</span>
                        <ReqMarker kind={r.mandatory ? 'must' : 'nice'} />
                      </span>
                    </div>
                    <div className="a-req-state">
                      {r.verification === 'VERIFIED' ? (
                        <Badge tone="success" dot>
                          Verified
                        </Badge>
                      ) : r.verification === 'CLAIMED' ? (
                        <Badge tone="neutral" variant="outline">
                          Your claim, unverified
                        </Badge>
                      ) : (
                        <Badge tone="neutral">Not yet verified</Badge>
                      )}
                      {r.verification !== 'VERIFIED' && detail.status !== 'WITHDRAWN' && (
                        <span className="a-req-actions">
                          <Button size="sm" variant="ghost" icon={Plus} onClick={() => setEvidenceFor(r)}>
                            Add evidence<span className="sr-only"> for {r.description}</span>
                          </Button>
                          <Link {...buttonProps('ghost', 'sm')} to="/verification?add=source&step=choose">
                            <Icon icon={BadgeCheck} size={14} />
                            Verify with a source<span className="sr-only">: {r.description}</span>
                          </Link>
                        </span>
                      )}
                    </div>
                  </li>
                ))}
              </EnterList>
              </>
            )}
          </section>
        </div>

        <aside className="c-rail" aria-label="Details">
          <div className="c-rail-block">
            <p className="c-rail-title">What you sent</p>
            <Facts
              items={[
                { label: 'Resume', value: <Link to="/resumes">{detail.resumeFilename}</Link> },
                { label: 'Cover letter', value: detail.coverLetter?.trim() ? 'Included' : 'Not included' },
              ]}
            />
            {detail.coverLetter?.trim() && (
              <details className="a-cover">
                <summary>Read your cover letter</summary>
                <p className="a-cover-text">{detail.coverLetter}</p>
              </details>
            )}
          </div>
          <div className="c-rail-block">
            <p className="c-rail-title">Work samples</p>
            {detail.workSamples.length === 0 ? (
              <p className="c-meta">None assigned for this application.</p>
            ) : (
              <ul className="a-mini">
                {detail.workSamples.map(s => (
                  <li key={s.assignmentId}>
                    <Link to={`/work-samples/${s.assignmentId}`}>{s.title}</Link>
                    <span className="c-meta">
                      {workSampleStatusLabel(s.status)} · {s.timeLimitMinutes} minutes
                    </span>
                  </li>
                ))}
              </ul>
            )}
          </div>
          <div className="c-rail-block">
            <p className="c-rail-title">Interviews</p>
            {detail.interviews.length === 0 ? (
              <p className="c-meta">None scheduled yet.</p>
            ) : (
              <ul className="a-mini">
                {detail.interviews.map(i => {
                  const r = timeRange(i.scheduledAt, i.durationMinutes)
                  return (
                    <li key={i.id}>
                      <span className="t-strong">
                        {r.day}, {r.time}
                      </span>
                      <span className="c-meta">
                        {humanize(i.status)} · {timeZoneLabel(new Date(i.scheduledAt))}
                      </span>
                    </li>
                  )
                })}
              </ul>
            )}
          </div>
        </aside>
      </div>

      <ConfirmDialog
        open={confirmWithdraw}
        title={`Withdraw your application for ${detail.jobTitle}?`}
        confirmLabel="Withdraw application"
        busy={withdraw.isPending}
        error={withdraw.error}
        onCancel={() => {
          setConfirmWithdraw(false)
          withdraw.reset()
        }}
        onConfirm={() => withdraw.mutate()}
      >
        <p>The employer stops considering you. You cannot undo this, and the employer may not accept a new application for the same job.</p>
      </ConfirmDialog>

      <Drawer
        open={evidenceFor !== null}
        onClose={closeEvidence}
        title="Add evidence"
        description={evidenceFor ? `For “${evidenceFor.description}”. Your claim is added to your evidence for every employer you apply to.` : undefined}
      >
        {evidenceFor && (
          <ClaimForm
            initialType={claimTypeFor(evidenceFor.type)}
            initialValue={evidenceFor.description}
            submitLabel="Add evidence"
            onDirtyChange={setEvidenceDirty}
            onCancel={closeEvidence}
            onCreated={() => {
              setEvidenceFor(null)
              notify('success', 'Evidence added', { body: 'Recruiters see it as your claim until it is checked. Matching re-runs now.' })
            }}
          />
        )}
      </Drawer>
      <ConfirmDialog
        open={confirmDiscard}
        title="Discard this evidence?"
        confirmLabel="Discard"
        onCancel={() => setConfirmDiscard(false)}
        onConfirm={() => {
          setConfirmDiscard(false)
          setEvidenceFor(null)
        }}
      >
        <p>What you changed has not been added yet and will be lost.</p>
      </ConfirmDialog>
    </>
  )
}

/** The one thing to do now, directly under the header (UX C-1). */
function NextStepPanel({ detail }: { detail: ApplicationDetail }) {
  const step = nextStepFor(detail, detail.workSamples, detail.interviews)
  if (step.kind === 'closed') return null

  if ((step.kind === 'work-sample' || step.kind === 'work-sample-started') && step.sample && step.to) {
    const s = step.sample
    const started = step.kind === 'work-sample-started'
    return (
      <Panel className="a-next-panel" aria-label="Next step">
        <span className="a-next-icon">
          <Icon icon={ListChecks} size={20} />
        </span>
        <div className="a-next-body">
          <p className="a-next-label">Next step</p>
          <p className="t-h3">{started ? `Finish “${s.title}”` : `Work sample: ${s.title}`}</p>
          <p className="c-muted">
            {started
              ? `In progress${s.deadlineAt ? `, ends ${relativeTime(s.deadlineAt)}` : ''}. The timer keeps running.`
              : `${s.timeLimitMinutes} minutes once you start${s.dueAt ? ` · start by ${formatDate(s.dueAt, { weekday: 'short', day: 'numeric', month: 'short' })} (${relativeTime(s.dueAt)})` : ''}. Instructions appear when you start.`}
          </p>
        </div>
        <Link {...buttonProps('primary')} to={step.to}>
          {started ? 'Continue' : 'Open work sample'}
          <Icon icon={ArrowRight} size={16} />
        </Link>
      </Panel>
    )
  }

  if (step.kind === 'interview' && step.interview) {
    const i = step.interview
    const r = timeRange(i.scheduledAt, i.durationMinutes)
    const live = Date.now() >= new Date(i.scheduledAt).getTime() - 15 * 60_000 && Date.now() <= interviewEnd(i)
    return (
      <Panel className="a-next-panel" aria-label="Next step">
        <span className="a-next-icon">
          <Icon icon={CalendarClock} size={20} />
        </span>
        <div className="a-next-body">
          <p className="a-next-label">Next step · interview {relativeTime(i.scheduledAt)}</p>
          <p className="t-h3">
            {r.day}, {r.time}
          </p>
          <p className="c-muted">
            {timeZoneLabel(new Date(i.scheduledAt))} · {i.durationMinutes} minutes
            {i.location && !isHttpUrl(i.location) ? ` · ${i.location}` : ''}
          </p>
        </div>
        <div className="a-next-actions">
          <Button icon={Calendar} onClick={() => downloadInterviewIcs(i, detail.jobTitle)}>
            Add to calendar
          </Button>
          {i.location && isHttpUrl(i.location) && (
            <a {...buttonProps(live ? 'primary' : 'secondary')} href={i.location} target="_blank" rel="noopener noreferrer">
              Join
              <Icon icon={ArrowUpRight} size={16} />
              <span className="sr-only"> (opens in a new tab)</span>
            </a>
          )}
        </div>
      </Panel>
    )
  }

  return (
    <Panel className="a-next-panel" data-quiet="" aria-label="Next step">
      <span className="a-next-icon">
        <Icon icon={step.kind === 'selected' ? BadgeCheck : Clock} size={20} />
      </span>
      <div className="a-next-body">
        <p className="a-next-label">Next step</p>
        <p className="t-h3">{step.kind === 'selected' ? 'The employer will contact you about next steps' : 'Nothing needed from you right now'}</p>
        <p className="c-muted">
          {step.kind === 'selected'
            ? 'Congratulations. Keep an eye on your email and inbox.'
            : 'We notify you when the employer moves forward. Meanwhile, stronger evidence helps every application.'}
        </p>
      </div>
      {step.kind !== 'selected' && (
        <Link {...buttonProps('secondary')} to="/verification">
          Strengthen your evidence
        </Link>
      )}
    </Panel>
  )
}

function DetailSkeleton() {
  return (
    <LoadingRegion label="Loading application">
      <Skeleton width={180} height={10} />
      <Skeleton width={300} height={24} style={{ marginTop: 12 }} />
      <Skeleton width="100%" height={88} style={{ marginTop: 24, borderRadius: 6 }} />
      <div style={{ marginTop: 32 }}>
        <SkeletonText lines={5} />
      </div>
    </LoadingRegion>
  )
}

/** "3 of 5 verified · must-haves 2 of 3": the summary before the per-requirement rows (count-up on first view). */
function RequirementSummary({ requirements }: { requirements: ApplicationRequirement[] }) {
  const verified = requirements.filter(r => r.verification === 'VERIFIED').length
  const must = requirements.filter(r => r.mandatory)
  const mustVerified = must.filter(r => r.verification === 'VERIFIED').length
  return (
    <div className="a-req-summary">
      <p className="a-req-summary-main">
        <span className="a-req-summary-n numeric">
          <CountUp value={verified} /> of {requirements.length}
        </span>{' '}
        requirements verified
      </p>
      {must.length > 0 && (
        <p className="c-meta">
          Must-haves: {mustVerified} of {must.length} verified
          {mustVerified < must.length ? ' · add evidence to the others below' : ''}
        </p>
      )}
      <div className="a-req-bar" aria-hidden="true">
        {requirements.map((r, i) => (
          <span key={r.requirementId} style={{ ['--i' as string]: i }} data-on={r.verification === 'VERIFIED' || undefined} data-claim={r.verification === 'CLAIMED' || undefined} />
        ))}
      </div>
    </div>
  )
}
