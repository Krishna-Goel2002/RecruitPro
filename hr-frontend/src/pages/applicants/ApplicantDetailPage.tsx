import { useMemo, useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useLocation, useNavigate, useParams } from 'react-router-dom'
import { errorMessage } from '../../api/client'
import { jobsApi } from '../../api/jobs'
import { qk } from '../../api/queryKeys'
import { reviewApi } from '../../api/review'
import { verificationApi } from '../../api/verification'
import type { ApplicantDetail, DecisionAction } from '../../api/types'
import { useOptionalAssistant } from '../../assistant/AssistantProvider'
import { useAuth } from '../../auth/AuthContext'
import { ActionDialog } from '../../components/ActionDialog'
import { QueryError } from '../../components/QueryError'
import { ShortcutSheet, useShortcuts } from '../../components/Shortcuts'
import { ApplicationStatusBadge } from '../../components/StatusBadges'
import {
  Avatar, Button, IconButton, LoadingRegion, PageHeader, Skeleton, SkeletonText, TabPanel, Tabs, Tag, useMediaQuery, useToast,
} from '../../design/components'
import { ChevronLeft, ChevronRight, Eye, EyeOff, FileText, Icon, Link2, MessagesSquare, ScrollText, Users } from '../../design/icons'
import { relativeTime } from '../../design/lib/format'
import { usePendingPoll, usePollRef } from '../../lib/hooks'
import { useSearchState } from '../../lib/useSearchState'
import { ClaimsTab } from './ClaimsTab'
import { DecisionBar, SelectCheck } from './DecisionBar'
import { EvidenceTab, GlanceStrip } from './EvidenceTab'
import { IdentityRail } from './IdentityRail'
import { InterviewsTab, ScheduleDialog } from './InterviewsTab'
import { ACTIONS, listPosition, REJECT_REASONS } from './model'
import { VerificationTab } from './VerificationTab'
import { WorkSamplesTab } from './WorkSamplesTab'
import './applicant.css'

type TabKey = 'evidence' | 'claims' | 'work-samples' | 'interviews' | 'verification' | 'profile'

/**
 * /jobs/:jobId/applicants/:applicationId — the applicant review (FLOWS-HR-B §1): evidence at a glance, per-requirement
 * drill-down, verification proof, then the decision in a sticky bar. Blind-first: nothing identifying is requested
 * before reveal, and reveal is always an explicit, audited act.
 */
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

  if (detail.isPending) return <ApplicantSkeleton />
  if (detail.isError) {
    return (
      <div className="ap" data-width="wide">
        <PageHeader title="Applicant" breadcrumb={[{ label: 'Jobs', to: '/jobs' }, { label: job.data?.title ?? 'Job', to: `/jobs/${jobId}` }]} />
        <QueryError error={detail.error} title="We couldn’t load this applicant" onRetry={() => void detail.refetch()} retrying={detail.isFetching} />
      </div>
    )
  }
  return (
    <ApplicantView
      key={applicationId}
      jobId={jobId}
      jobTitle={job.data?.title}
      detail={detail.data}
      polling={poll.polling}
      pollTimedOut={poll.timedOut}
      onCheckAgain={() => { poll.restart(); void detail.refetch() }}
    />
  )
}

function ApplicantView({ jobId, jobTitle, detail, polling, pollTimedOut, onCheckAgain }: {
  jobId: number
  jobTitle?: string
  detail: ApplicantDetail
  polling: boolean
  pollTimedOut: boolean
  onCheckAgain: () => void
}) {
  const { can } = useAuth()
  const assistant = useOptionalAssistant()
  const queryClient = useQueryClient()
  const { toast } = useToast()
  const navigate = useNavigate()
  const location = useLocation()
  const search = useSearchState()
  const wideRail = useMediaQuery('(min-width: 1200px)')
  const noteRef = useRef<HTMLTextAreaElement>(null)
  const [action, setAction] = useState<DecisionAction | null>(null)
  const [revealOpen, setRevealOpen] = useState(false)
  const [scheduleOpen, setScheduleOpen] = useState(false)
  const [helpOpen, setHelpOpen] = useState(false)

  const state = location.state as { back?: unknown; order?: unknown } | null
  const backTo = typeof state?.back === 'string' ? state.back : `/jobs/${jobId}?tab=applicants`
  const position = useMemo(() => listPosition(queryClient, jobId, detail.applicationId, state?.order), [queryClient, jobId, detail.applicationId, state?.order])
  const prevId = position && position.index > 0 ? position.ids[position.index - 1] : null
  const nextId = position && position.index < position.ids.length - 1 ? position.ids[position.index + 1] : null
  const go = (id: number | null) => {
    if (id == null) return
    const tab = search.get('tab')
    navigate(`/jobs/${jobId}/applicants/${id}${tab ? `?tab=${tab}` : ''}`, { state: location.state })
  }

  // Verification summary for the tab count (cheap; the tab itself reuses the same cache entry).
  const verification = useQuery({
    queryKey: qk.applicantVerification(detail.applicationId),
    queryFn: ({ signal }) => verificationApi.forApplication(detail.applicationId, signal),
    enabled: can('verification.view'),
  })

  const detailKey = qk.applicant(jobId, detail.applicationId)
  const refreshAround = () => {
    void queryClient.invalidateQueries({ queryKey: qk.applicants(jobId) })
    void queryClient.invalidateQueries({ queryKey: qk.job(jobId) })
    void queryClient.invalidateQueries({ queryKey: qk.applicantVerification(detail.applicationId) })
  }

  const decide = useMutation({
    mutationFn: ({ next, comment }: { next: DecisionAction; comment: string; undo?: boolean }) =>
      reviewApi.decide(jobId, detail.applicationId, { action: next, comment: comment || undefined }),
    onSuccess: (_event, variables) => {
      setAction(null)
      const spec = ACTIONS[variables.next]
      void queryClient.invalidateQueries({ queryKey: detailKey })
      refreshAround()
      if (variables.undo) {
        toast({ tone: 'info', title: `Undone: ${spec.doneTitle.toLowerCase()}`, body: 'Recorded in the decision history.' })
        return
      }
      const inverse = spec.inverse
      toast({
        tone: 'success',
        title: spec.doneTitle,
        body: variables.next === 'REJECT' ? 'The candidate is notified.' : 'Recorded in the decision history.',
        duration: inverse ? 8000 : undefined,
        action: inverse
          ? {
              label: 'Undo',
              onClick: () => decide.mutate({ next: inverse, comment: `Undone from the confirmation toast right after “${spec.label}”.`, undo: true }),
            }
          : undefined,
      })
    },
    onError: (cause, variables) => {
      if (variables.undo) toast({ tone: 'danger', title: 'Could not undo', body: errorMessage(cause) })
    },
  })

  const reveal = useMutation({
    mutationFn: () => reviewApi.reveal(jobId, detail.applicationId),
    onSuccess: revealed => {
      setRevealOpen(false)
      queryClient.setQueryData(detailKey, revealed)
      refreshAround()
      toast({ tone: 'success', title: 'Identity revealed', body: 'Recorded in the audit log with your name.' })
    },
  })

  const retry = useMutation({
    mutationFn: () => reviewApi.retryAnalysis(jobId, detail.applicationId),
    onSuccess: () => {
      toast({ tone: 'info', title: 'Analysis queued again', body: 'Results appear here when it finishes.' })
      void queryClient.invalidateQueries({ queryKey: detailKey })
      refreshAround()
    },
    onError: cause => toast({ tone: 'danger', title: 'The analysis could not be queued', body: errorMessage(cause) }),
  })

  const canReveal = can('applicants.reveal') && !detail.identityRevealed && (detail.revealAllowed ?? detail.applicationStatus !== 'SUBMITTED')

  // ------------------------------------------------------------------ tabs
  const verifCount = verification.data?.mode === 'VISIBLE' ? verification.data.claims.length : undefined
  const tabs = [
    { value: 'evidence' as const, label: 'Evidence', count: detail.requirements.length },
    { value: 'claims' as const, label: 'Claims', count: detail.claims.length },
    { value: 'work-samples' as const, label: 'Work samples', count: detail.workSamples.length },
    { value: 'interviews' as const, label: 'Interviews', count: detail.interviews.length },
    ...(can('verification.view') ? [{ value: 'verification' as const, label: 'Verification', count: verifCount }] : []),
    ...(!wideRail ? [{ value: 'profile' as const, label: detail.identityRevealed ? 'Candidate & notes' : 'Identity & notes' }] : []),
  ]
  const requested = search.get('tab', 'evidence')
  const normalised = (requested === 'review' ? 'evidence' : requested) as TabKey
  const active: TabKey = tabs.some(t => t.value === normalised) ? normalised : 'evidence'
  const setTab = (value: TabKey) => search.set({ tab: value === 'evidence' ? null : value }, { replace: true })

  useShortcuts({
    '[': () => go(prevId),
    ']': () => go(nextId),
    ...Object.fromEntries(tabs.map((t, i) => [String(i + 1), () => setTab(t.value)])),
    n: () => {
      if (!wideRail) setTab('profile')
      window.setTimeout(() => noteRef.current?.focus(), 30)
    },
    '?': () => setHelpOpen(true),
  })

  const name = detail.identityRevealed && detail.candidate ? detail.candidate.fullName : detail.displayName
  const spec = action ? ACTIONS[action] : null

  const rail = (
    <IdentityRail
      jobId={jobId}
      detail={detail}
      canReveal={canReveal}
      onReveal={() => { reveal.reset(); setRevealOpen(true) }}
      noteRef={noteRef}
    />
  )

  return (
    <div className="ap" data-width="wide">
      <PageHeader
        breadcrumb={[
          { label: 'Jobs', to: '/jobs' },
          { label: jobTitle ?? 'Job', to: `/jobs/${jobId}` },
          { label: 'Applicants', to: backTo },
        ]}
        documentTitle={`${detail.displayName} · ${jobTitle ?? 'Applicant'}`}
        title={
          <span className="ap-title">
            <Avatar name={name} blind={!detail.identityRevealed} size={32} />
            <span className="ap-title-text">{name}</span>
          </span>
        }
        status={
          <span className="ap-title-tags">
            <ApplicationStatusBadge status={detail.applicationStatus} />
            {!detail.identityRevealed && <Tag icon={EyeOff}>Blind review</Tag>}
          </span>
        }
        meta={[
          detail.identityRevealed ? <span key="id" className="mono">A-{detail.applicationId}</span> : null,
          <span key="applied">Applied <time dateTime={detail.appliedAt} title={new Date(detail.appliedAt).toLocaleString('en-IN')}>{relativeTime(detail.appliedAt)}</time></span>,
          detail.rankGroup === 'RANKED' && detail.rank != null ? <span key="rank">Rank <span className="numeric">#{detail.rank}</span></span> : null,
          detail.statusChangedAt ? <span key="changed">Status changed {relativeTime(detail.statusChangedAt)}</span> : null,
        ]}
        actions={
          <>
            {assistant && (
              <Button icon={MessagesSquare} onClick={() => assistant.ask('Tell me about this applicant')} aria-label="Ask the assistant about this applicant">Ask</Button>
            )}
            {canReveal && (
              <Button icon={Eye} onClick={() => { reveal.reset(); setRevealOpen(true) }}>Reveal identity</Button>
            )}
            {position && position.ids.length > 1 && (
              <div className="ap-pager" role="group" aria-label="Move between applicants">
                <IconButton label="Previous applicant ( [ )" icon={ChevronLeft} variant="secondary" disabled={prevId == null} onClick={() => go(prevId)} />
                <span className="ap-pager-pos numeric" aria-live="polite">{position.index + 1} of {position.ids.length}</span>
                <IconButton label="Next applicant ( ] )" icon={ChevronRight} variant="secondary" disabled={nextId == null} onClick={() => go(nextId)} />
              </div>
            )}
          </>
        }
      />

      <GlanceStrip
        detail={detail}
        polling={polling}
        pollTimedOut={pollTimedOut}
        onCheckAgain={onCheckAgain}
        canRetry={can('applicants.retryAnalysis')}
        retrying={retry.isPending}
        onRetry={() => retry.mutate()}
        onOpenRequirement={id => {
          setTab('evidence')
          window.setTimeout(() => {
            const row = document.getElementById(`req-${id}`)
            row?.dispatchEvent(new CustomEvent('ap:open', { bubbles: false }))
            row?.scrollIntoView({ behavior: 'smooth', block: 'center' })
            row?.querySelector<HTMLElement>('.ap-req-toggle')?.focus({ preventScroll: true })
          }, 40)
        }}
      />

      <div className="ap-layout">
        <div className="ap-main">
          <Tabs label="Applicant sections" idBase="ap" items={tabs} value={active} onChange={v => setTab(v as TabKey)} />
          <TabPanel idBase="ap" value="evidence" active={active}><EvidenceTab jobId={jobId} detail={detail} /></TabPanel>
          <TabPanel idBase="ap" value="claims" active={active}><ClaimsTab jobId={jobId} detail={detail} /></TabPanel>
          <TabPanel idBase="ap" value="work-samples" active={active}><WorkSamplesTab jobId={jobId} detail={detail} /></TabPanel>
          <TabPanel idBase="ap" value="interviews" active={active}><InterviewsTab jobId={jobId} detail={detail} onSchedule={() => setScheduleOpen(true)} /></TabPanel>
          {can('verification.view') && (
            <TabPanel idBase="ap" value="verification" active={active}>
              <VerificationTab jobId={jobId} applicationId={detail.applicationId} query={verification} />
            </TabPanel>
          )}
          {!wideRail && <TabPanel idBase="ap" value="profile" active={active}><div className="ap-rail-inline">{rail}</div></TabPanel>}

          <DecisionBar
            detail={detail}
            canDecide={can('applicants.decide')}
            canSchedule={can('interviews.schedule')}
            onAction={next => { decide.reset(); setAction(next) }}
            onSchedule={() => setScheduleOpen(true)}
            onHelp={() => setHelpOpen(true)}
          />
        </div>
        {wideRail && <aside className="ap-rail" aria-label="Candidate, review note and history">{rail}</aside>}
      </div>

      <ActionDialog
        open={action != null}
        title={action === 'SHORTLIST' && detail.identityRevealed ? 'Shortlist this applicant?' : spec?.title ?? ''}
        description={action === 'SHORTLIST' && detail.identityRevealed ? 'The identity is already revealed. The candidate is notified.' : spec?.description}
        confirmLabel={action === 'SHORTLIST' && detail.identityRevealed ? 'Shortlist' : spec?.confirm ?? 'Confirm'}
        tone={spec?.tone}
        size={action === 'SELECT' ? 'lg' : 'md'}
        busy={decide.isPending}
        error={decide.error}
        comment={{
          label: action === 'REJECT' ? 'Reason' : 'Comment',
          required: spec?.commentRequired,
          placeholder: spec?.commentRequired ? 'Why this change? Kept in the decision history.' : 'Optional: why, for the decision history',
          hint: action === 'REJECT' ? 'Kept in the decision history for your team. The candidate only sees “Not progressed”.' : undefined,
          suggestions: action === 'REJECT' ? REJECT_REASONS : undefined,
          maxLength: 1000,
        }}
        onCancel={() => setAction(null)}
        onConfirm={comment => { if (action) decide.mutate({ next: action, comment }) }}
      >
        {action === 'SHORTLIST' && !detail.identityRevealed && <RevealConsequences />}
        {action === 'SELECT' && <SelectCheck detail={detail} />}
      </ActionDialog>

      <ActionDialog
        open={revealOpen}
        title="Reveal this candidate’s identity?"
        description="Do this when you need contact details before shortlisting. It cannot be undone."
        confirmLabel="Reveal identity"
        focusCancel
        busy={reveal.isPending}
        error={reveal.error}
        onCancel={() => setRevealOpen(false)}
        onConfirm={() => reveal.mutate()}
      >
        <RevealConsequences />
      </ActionDialog>

      <ScheduleDialog
        open={scheduleOpen}
        jobId={jobId}
        detail={detail}
        onClose={() => setScheduleOpen(false)}
        onScheduled={() => {
          setScheduleOpen(false)
          setTab('interviews')
        }}
      />

      <ShortcutSheet
        open={helpOpen}
        onClose={() => setHelpOpen(false)}
        groups={[
          { title: 'Applicants', items: [{ keys: ['['], label: 'Previous applicant' }, { keys: [']'], label: 'Next applicant' }] },
          { title: 'This applicant', items: [
            ...tabs.map((t, i) => ({ keys: [String(i + 1)], label: `${t.label} tab` })),
            { keys: ['N'], label: 'Write a review note' },
            { keys: ['Ctrl Enter'], label: 'Save the note or confirm a dialog' },
          ] },
          { title: 'Help', items: [{ keys: ['?'], label: 'Show these shortcuts' }] },
        ]}
      />
    </div>
  )
}

/** What a reveal exposes, and to whom (FLOWS-HR-B §1: the dialog names exactly what becomes visible). */
function RevealConsequences() {
  return (
    <ul className="sx-consequences" aria-label="What becomes visible">
      <li><Icon icon={Users} size={16} /><span>Visible to everyone reviewing this job<span className="sx-cons-sub">Managers and recruiters of your organisation with access to the job.</span></span></li>
      <li><Icon icon={Eye} size={16} /><span>Name, email, location and current role</span></li>
      <li><Icon icon={FileText} size={16} /><span>Resume file and cover letter</span></li>
      <li><Icon icon={Link2} size={16} /><span>Claim evidence links and verification sources</span></li>
      <li><Icon icon={ScrollText} size={16} /><span>Recorded in the audit log with your name and the time</span></li>
    </ul>
  )
}

function ApplicantSkeleton() {
  return (
    <div className="ap" data-width="wide">
      <LoadingRegion label="Loading applicant">
        <div className="ap-skel-head">
          <Skeleton width={180} height={10} />
          <div className="ap-skel-title"><Skeleton width={32} height={32} radius={16} /><Skeleton width={260} height={20} /></div>
          <Skeleton width={340} height={10} />
        </div>
        <div className="ap-glance ap-skel-glance">
          <div><Skeleton width={220} height={8} /><Skeleton width={220} height={8} style={{ marginTop: 14 }} /></div>
          <div className="ap-skel-cells">{Array.from({ length: 6 }, (_, i) => <Skeleton key={i} width={10} height={18} radius={2} />)}</div>
        </div>
        <div className="ap-layout">
          <div className="ap-main">
            <Skeleton width={420} height={12} />
            {Array.from({ length: 5 }, (_, i) => (
              <div key={i} className="ap-skel-row"><SkeletonText lines={2} /><Skeleton width={90} height={14} /><SkeletonText lines={2} /></div>
            ))}
          </div>
          <div className="ap-rail ap-skel-rail"><SkeletonText lines={5} /></div>
        </div>
      </LoadingRegion>
    </div>
  )
}

