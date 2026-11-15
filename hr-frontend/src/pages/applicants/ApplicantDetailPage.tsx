import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useLocation, useParams } from 'react-router-dom'
import { errorMessage } from '../../api/client'
import { jobsApi } from '../../api/jobs'
import { qk } from '../../api/queryKeys'
import { reviewApi } from '../../api/review'
import type { ApplicantDetail, DecisionAction } from '../../api/types'
import { useAuth } from '../../auth/AuthContext'
import { StatusBadge } from '../../components/Badges'
import { ConfirmDialog, TextPromptDialog } from '../../components/Dialog'
import { EyeIcon, RefreshIcon } from '../../components/Icons'
import { useNotices } from '../../components/Notices'
import { ErrorPanel, LoadingPanel } from '../../components/States'
import { Tabs } from '../../components/Tabs'
import { formatDateTime } from '../../lib/format'
import { usePageTitle, usePendingPoll, usePollRef } from '../../lib/hooks'
import { useSearchState } from '../../lib/useSearchState'
import { CandidateSection } from './CandidateSection'
import { ClaimsSection, EvidenceSection } from './EvidenceSection'
import { HistorySection } from './HistorySection'
import { InterviewSection } from './InterviewSection'
import { VerificationTab } from './VerificationTab'
import { WorkSampleSection } from './WorkSampleSection'

type ApplicantTab = 'review' | 'verification'

type ActionCopy = { label: string; title: string; message: string; commentRequired: boolean; danger?: boolean; primary?: boolean }

const ACTIONS: Record<DecisionAction, ActionCopy> = {
  SHORTLIST: { label: 'Shortlist', title: 'Shortlist this applicant?', message: 'Shortlisting reveals the candidate’s identity, resume and cover letter to the team. The reveal is audited.', commentRequired: false, primary: true },
  REJECT: { label: 'Reject', title: 'Reject this application?', message: 'The candidate is notified. You can reopen it later with a reason.', commentRequired: false, danger: true },
  UNDO_SHORTLIST: { label: 'Undo shortlist', title: 'Undo the shortlist?', message: 'The application returns to Submitted. Identity stays revealed. Say why for the audit trail.', commentRequired: true },
  REOPEN: { label: 'Reopen', title: 'Reopen this application?', message: 'The application returns to Submitted. Say why for the audit trail.', commentRequired: true },
  MOVE_TO_INTERVIEW: { label: 'Move to interview', title: 'Move to interview?', message: 'Scheduling the first interview also does this automatically.', commentRequired: false, primary: true },
  SELECT: { label: 'Select', title: 'Select this candidate?', message: 'Selected is the final stage in RecruitPro; the hire is handed off to your offer process.', commentRequired: false, primary: true },
}

const ACTION_ORDER: DecisionAction[] = ['SHORTLIST', 'MOVE_TO_INTERVIEW', 'SELECT', 'UNDO_SHORTLIST', 'REOPEN', 'REJECT']

/** /jobs/:jobId/applicants/:applicationId — blind-first review with evidence, claims, work samples, interviews (C3). */
export function ApplicantDetailPage() {
  const params = useParams()
  const jobId = Number(params.jobId)
  const applicationId = Number(params.applicationId)
  const pollRef = usePollRef()
  const detail = useQuery({
    queryKey: qk.applicant(jobId, applicationId),
    queryFn: ({ signal }) => reviewApi.detail(jobId, applicationId, signal),
    refetchInterval: pollRef.interval,
  })
  const poll = usePendingPoll(detail.data?.matchStatus === 'PENDING', `${jobId}:${applicationId}`)
  pollRef.bind(poll.refetchInterval)
  const job = useQuery({ queryKey: qk.job(jobId), queryFn: ({ signal }) => jobsApi.get(jobId, signal) })
  usePageTitle(detail.data ? `${detail.data.displayName} · ${job.data?.title ?? 'Applicant'}` : 'Applicant')

  if (detail.isPending) return <LoadingPanel label="Loading applicant…" />
  if (detail.isError) return <ErrorPanel error={detail.error} title="We couldn’t load this applicant" onRetry={() => void detail.refetch()} />
  return <ApplicantView jobId={jobId} jobTitle={job.data?.title} detail={detail.data} pollTimedOut={poll.timedOut} onCheckAgain={() => { poll.restart(); void detail.refetch() }} />
}

function ApplicantView({ jobId, jobTitle, detail, pollTimedOut, onCheckAgain }: {
  jobId: number
  jobTitle?: string
  detail: ApplicantDetail
  pollTimedOut: boolean
  onCheckAgain: () => void
}) {
  const { can } = useAuth()
  const queryClient = useQueryClient()
  const notices = useNotices()
  const location = useLocation()
  const search = useSearchState()
  const tabs: { key: ApplicantTab; label: string }[] = [
    { key: 'review', label: 'Evidence and assessments' },
    ...(can('verification.view') ? [{ key: 'verification' as const, label: 'Verification' }] : []),
  ]
  const requestedTab = search.get('tab', 'review') as ApplicantTab
  const activeTab: ApplicantTab = tabs.some(tab => tab.key === requestedTab) ? requestedTab : 'review'
  const [action, setAction] = useState<DecisionAction | null>(null)
  const [revealOpen, setRevealOpen] = useState(false)
  const backTo = typeof (location.state as { back?: unknown } | null)?.back === 'string' ? (location.state as { back: string }).back : `/jobs/${jobId}`

  const detailKey = qk.applicant(jobId, detail.applicationId)
  const refreshLists = () => {
    void queryClient.invalidateQueries({ queryKey: qk.applicants(jobId) })
    void queryClient.invalidateQueries({ queryKey: qk.job(jobId) })
    // A reveal (explicit or by shortlisting) unlocks verification evidence and the candidate's notes.
    void queryClient.invalidateQueries({ queryKey: qk.applicantVerification(detail.applicationId) })
  }

  const decide = useMutation({
    mutationFn: ({ next, comment }: { next: DecisionAction; comment: string }) => reviewApi.decide(jobId, detail.applicationId, { action: next, comment: comment || undefined }),
    onSuccess: (event, variables) => {
      setAction(null)
      notices.success(`${ACTIONS[variables.next].label}: the application is now ${event.newStatus.toLowerCase().replace(/_/g, ' ')}.`)
      void queryClient.invalidateQueries({ queryKey: detailKey })
      refreshLists()
    },
  })

  const reveal = useMutation({
    mutationFn: () => reviewApi.reveal(jobId, detail.applicationId),
    onSuccess: revealed => {
      setRevealOpen(false)
      queryClient.setQueryData(detailKey, revealed)
      refreshLists()
      notices.success('Identity revealed. This is recorded in the audit log.')
    },
  })

  const retry = useMutation({
    mutationFn: () => reviewApi.retryAnalysis(jobId, detail.applicationId),
    onSuccess: () => {
      notices.success('Resume analysis queued again. Results appear here when it finishes.')
      void queryClient.invalidateQueries({ queryKey: detailKey })
      refreshLists()
    },
    onError: cause => notices.error(errorMessage(cause, 'The analysis could not be queued.')),
  })

  const allowed = can('applicants.decide') ? ACTION_ORDER.filter(item => detail.allowedActions?.includes(item)) : []
  // Prefer the server's revealAllowed (it knows whether blind review is on); fall back to the plan's rule.
  const canReveal = can('applicants.reveal') && !detail.identityRevealed && (detail.revealAllowed ?? detail.applicationStatus !== 'SUBMITTED')
  const analysisFailed = detail.analysisStatus === 'FAILED' || detail.matchStatus === 'FAILED'
  const copy = action ? ACTIONS[action] : null

  return (
    <>
      <Link className="back-link" to={backTo}>← Back to {jobTitle ? `“${jobTitle}”` : 'applicants'}</Link>

      <section className="detail-card applicant-header">
        <div className="detail-heading">
          <div>
            <p className="eyebrow">{jobTitle ? `${jobTitle.toUpperCase()} · ` : ''}APPLICATION #A-{detail.applicationId}</p>
            <h1>{detail.displayName}</h1>
            <div className="detail-tags">
              {!detail.identityRevealed && <span className="blind-pill">Blind review</span>}
              <span>Applied {formatDateTime(detail.appliedAt)}</span>
              {detail.statusChangedAt && <span>Status changed {formatDateTime(detail.statusChangedAt)}</span>}
              {detail.rankGroup === 'RANKED' && detail.rank != null && <span>Rank #{detail.rank}</span>}
            </div>
          </div>
          <StatusBadge status={detail.applicationStatus} />
        </div>
        {(allowed.length > 0 || canReveal || (analysisFailed && can('applicants.retryAnalysis')) || pollTimedOut) && (
          <div className="detail-actions decision-bar" role="group" aria-label="Decisions">
            {pollTimedOut && <button type="button" className="outline-button" onClick={onCheckAgain}><RefreshIcon /> Matching is slow — check again</button>}
            {analysisFailed && can('applicants.retryAnalysis') && (
              <button type="button" className="outline-button" onClick={() => retry.mutate()} disabled={retry.isPending}><RefreshIcon /> {retry.isPending ? 'Queuing…' : 'Retry analysis'}</button>
            )}
            {canReveal && <button type="button" className="outline-button" onClick={() => { reveal.reset(); setRevealOpen(true) }}><EyeIcon /> Reveal identity</button>}
            {allowed.map(item => (
              <button
                key={item}
                type="button"
                className={ACTIONS[item].danger ? 'outline-button danger-outline' : ACTIONS[item].primary ? 'primary-button workspace-primary' : 'outline-button'}
                onClick={() => { decide.reset(); setAction(item) }}
              >
                {ACTIONS[item].label}
              </button>
            ))}
          </div>
        )}
        {can('applicants.decide') && allowed.length === 0 && (detail.applicationStatus === 'SELECTED' || detail.applicationStatus === 'WITHDRAWN') && (
          <p className="muted-copy decision-note">{detail.applicationStatus === 'SELECTED' ? 'Selected: handed off to your offer process.' : 'The candidate withdrew this application.'}</p>
        )}
      </section>

      <div className="applicant-layout">
        <div className="applicant-main">
          <Tabs items={tabs} active={activeTab} onChange={key => search.set({ tab: key === 'review' ? null : key }, { replace: true })} label="Applicant sections">
            {activeTab === 'review' && <>
              <EvidenceSection detail={detail} />
              <ClaimsSection jobId={jobId} detail={detail} />
              <WorkSampleSection jobId={jobId} detail={detail} />
              <InterviewSection jobId={jobId} detail={detail} />
            </>}
            {activeTab === 'verification' && <VerificationTab jobId={jobId} applicationId={detail.applicationId} />}
          </Tabs>
        </div>
        <aside className="applicant-aside" aria-label="Candidate and history">
          <CandidateSection jobId={jobId} detail={detail} canReveal={canReveal} onReveal={() => { reveal.reset(); setRevealOpen(true) }} />
          <HistorySection jobId={jobId} detail={detail} />
        </aside>
      </div>

      <TextPromptDialog
        open={action != null}
        title={copy?.title ?? ''}
        message={copy?.message}
        label="Comment"
        required={copy?.commentRequired ?? false}
        placeholder={copy?.commentRequired ? 'Required: the reason for this change' : 'Optional: why, for the decision history'}
        confirmLabel={copy?.label ?? 'Confirm'}
        danger={copy?.danger}
        busy={decide.isPending}
        error={decide.error}
        onCancel={() => setAction(null)}
        onSubmit={comment => { if (action) decide.mutate({ next: action, comment }) }}
      />
      <ConfirmDialog
        open={revealOpen}
        title="Reveal this candidate’s identity?"
        message="Name, contact details, resume and cover letter become visible to everyone reviewing this job. The reveal is recorded in the audit log with your name."
        confirmLabel="Reveal identity"
        busy={reveal.isPending}
        error={reveal.error}
        onCancel={() => setRevealOpen(false)}
        onConfirm={() => reveal.mutate()}
      />
    </>
  )
}
