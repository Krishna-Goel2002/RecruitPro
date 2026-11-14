import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ApiError } from '../api/client'
import { applicationsApi } from '../api/endpoints'
import { queryKeys } from '../api/queryKeys'
import type { ApplicationDetail, ApplicationRequirement } from '../api/types'
import { MustHaveBadge, StatusBadge, VerificationBadge } from '../components/Badges'
import { ClaimForm } from '../components/ClaimForm'
import { ConfirmDialog, Dialog } from '../components/Dialog'
import { EmptyState, ErrorBlock, FormError, LoadingBlock } from '../components/Feedback'
import { useNotify } from '../components/Notices'
import { PageHeader, Section } from '../components/PageHeader'
import { focusPageHeading } from '../lib/focus'
import { formatDate, formatDateTime, formatLongDateTime, localTimeZone } from '../lib/format'
import {
  APPLICATION_STATUS_DESCRIPTION,
  REQUIREMENT_TYPE_LABEL,
  WITHDRAWABLE_STATUSES,
  applicationStatusLabel,
  claimTypeFor,
  humanize,
  workSampleStatusLabel,
} from '../lib/labels'

export function ApplicationDetailPage() {
  const { id } = useParams()
  const applicationId = Number(id)
  const valid = Number.isInteger(applicationId) && applicationId > 0
  const application = useQuery({
    queryKey: queryKeys.application(applicationId),
    queryFn: () => applicationsApi.get(applicationId),
    enabled: valid,
  })

  const backLink = (
    <Link className="back-link" to="/applications">
      ← All applications
    </Link>
  )

  if (!valid || (application.error instanceof ApiError && application.error.status === 404)) {
    return (
      <>
        {backLink}
        <PageHeader title="Application not found" />
        <EmptyState title="We could not find this application">It may belong to another account or the link is wrong.</EmptyState>
      </>
    )
  }
  if (application.isPending) return <LoadingBlock label="Loading application…" />
  if (application.isError) return <ErrorBlock error={application.error} onRetry={() => void application.refetch()} />

  return (
    <>
      {backLink}
      <ApplicationView detail={application.data} />
    </>
  )
}

function ApplicationView({ detail }: { detail: ApplicationDetail }) {
  const queryClient = useQueryClient()
  const notify = useNotify()
  const [confirmWithdraw, setConfirmWithdraw] = useState(false)
  const [evidenceFor, setEvidenceFor] = useState<ApplicationRequirement | null>(null)
  const canWithdraw = WITHDRAWABLE_STATUSES.includes(detail.status)

  const withdraw = useMutation({
    mutationFn: () => applicationsApi.withdraw(detail.id),
    onSuccess: () => {
      setConfirmWithdraw(false)
      notify('success', `Your application for ${detail.jobTitle} was withdrawn.`)
      void queryClient.invalidateQueries({ queryKey: queryKeys.applicationsAll })
      // The Withdraw button disappears with the new status; keep focus on the page.
      void queryClient.invalidateQueries({ queryKey: queryKeys.application(detail.id) }).then(focusPageHeading)
    },
  })

  return (
    <>
      <PageHeader
        eyebrow={`APPLICATION #${detail.id}`}
        title={detail.jobTitle}
        documentTitle={`Application: ${detail.jobTitle}`}
        description={
          <>
            {detail.organizationName ? `${detail.organizationName} · ` : ''}Applied {formatDateTime(detail.appliedAt)}
          </>
        }
        actions={
          <>
            <StatusBadge status={detail.status} />
            {canWithdraw && (
              <button type="button" className="danger-button" onClick={() => setConfirmWithdraw(true)}>
                Withdraw
              </button>
            )}
          </>
        }
      />

      <div className="detail-grid">
        <Section title="Progress" id="progress">
          <ol className="timeline">
            {detail.timeline.map((entry, index) => (
              <li key={`${entry.status}-${entry.at}-${index}`} className={index === detail.timeline.length - 1 ? 'current' : ''}>
                <span className="timeline-dot" aria-hidden="true" />
                <div>
                  <strong>{applicationStatusLabel(entry.status)}</strong>
                  <time dateTime={entry.at} className="muted">
                    {' '}
                    · {formatDateTime(entry.at)}
                  </time>
                  <p className="muted">{APPLICATION_STATUS_DESCRIPTION[entry.status] ?? ''}</p>
                </div>
              </li>
            ))}
          </ol>
        </Section>

        <Section title="What you sent" id="sent">
          <dl className="key-facts">
            <div>
              <dt>Resume</dt>
              <dd>
                <Link to="/resumes">{detail.resumeFilename}</Link>
              </dd>
            </div>
            <div>
              <dt>Job</dt>
              <dd>
                <Link to={`/jobs/${detail.jobId}`}>View job posting</Link>
              </dd>
            </div>
          </dl>
          <h3 className="subheading">Cover letter</h3>
          <div className="prose small">{detail.coverLetter?.trim() || <span className="muted">No cover letter was included.</span>}</div>
        </Section>
      </div>

      <Section
        title="Requirements and verification"
        id="requirements"
        description="Verified means the employer has evidence: your resume, a recruiter check of a claim, a work sample or an interview."
      >
        {detail.requirements === null ? (
          <p className="muted">
            {canWithdraw
              ? 'This employer does not share verification status with candidates.'
              : 'Verification status is shown while an application is active.'}
          </p>
        ) : detail.requirements.length === 0 ? (
          <p className="muted">This job has no structured requirements.</p>
        ) : (
          <ul className="requirement-list verification-list">
            {detail.requirements.map(requirement => (
              <li key={requirement.requirementId}>
                <div className="requirement-head">
                  <span className="fact-type-badge">{REQUIREMENT_TYPE_LABEL[requirement.type] ?? humanize(requirement.type)}</span>
                  <MustHaveBadge mandatory={requirement.mandatory} />
                  <VerificationBadge verification={requirement.verification} />
                </div>
                <p className="requirement-text">{requirement.description}</p>
                {requirement.verification !== 'VERIFIED' && detail.status !== 'WITHDRAWN' && (
                  <button
                    type="button"
                    className="text-button inline"
                    onClick={() => setEvidenceFor(requirement)}
                    aria-label={`Add evidence for ${requirement.description}`}
                  >
                    + Add evidence
                  </button>
                )}
              </li>
            ))}
          </ul>
        )}
      </Section>

      <div className="detail-grid">
        <Section title="Work samples" id="work-samples">
          {detail.workSamples.length === 0 ? (
            <p className="muted">No work sample has been assigned for this application.</p>
          ) : (
            <ul className="plain-list compact-list">
              {detail.workSamples.map(sample => (
                <li key={sample.assignmentId}>
                  <div>
                    <Link to={`/work-samples/${sample.assignmentId}`}>{sample.title}</Link>
                    <p className="muted">
                      {workSampleStatusLabel(sample.status)} · {sample.timeLimitMinutes} minutes
                      {sample.dueAt && sample.status === 'ASSIGNED' ? ` · start by ${formatDate(sample.dueAt)}` : ''}
                    </p>
                  </div>
                </li>
              ))}
            </ul>
          )}
        </Section>

        <Section title="Interviews" id="interviews" description={`Times are shown in ${localTimeZone()}.`}>
          {detail.interviews.length === 0 ? (
            <p className="muted">No interview has been scheduled yet.</p>
          ) : (
            <ul className="plain-list compact-list">
              {detail.interviews.map(interview => (
                <li key={interview.id}>
                  <div>
                    <strong>
                      <time dateTime={interview.scheduledAt}>{formatLongDateTime(interview.scheduledAt)}</time>
                    </strong>
                    <p className="muted">
                      {interview.durationMinutes} minutes · {humanize(interview.status)}
                      {interview.location ? ` · ${interview.location}` : ''}
                    </p>
                  </div>
                </li>
              ))}
            </ul>
          )}
        </Section>
      </div>

      <ConfirmDialog
        open={confirmWithdraw}
        title="Withdraw this application?"
        confirmLabel="Withdraw application"
        busy={withdraw.isPending}
        onCancel={() => {
          setConfirmWithdraw(false)
          withdraw.reset()
        }}
        onConfirm={() => withdraw.mutate()}
        error={<FormError error={withdraw.error} />}
      >
        <p>
          The employer will stop considering you for <strong>{detail.jobTitle}</strong>. You cannot undo this, and the
          employer may not accept a new application for the same job.
        </p>
      </ConfirmDialog>

      <Dialog
        open={evidenceFor !== null}
        title="Add evidence"
        onClose={() => setEvidenceFor(null)}
        description={
          evidenceFor && (
            <p>
              For the requirement <strong>{evidenceFor.description}</strong>. Your claim is added to your evidence and shared
              with employers you apply to.
            </p>
          )
        }
      >
        {evidenceFor && (
          <ClaimForm
            initialType={claimTypeFor(evidenceFor.type)}
            initialValue={evidenceFor.description}
            submitLabel="Add evidence"
            onCancel={() => setEvidenceFor(null)}
            onCreated={() => {
              setEvidenceFor(null)
              notify('success', 'Evidence added. Recruiters see it as unverified until they check it.')
            }}
          />
        )}
      </Dialog>
    </>
  )
}
