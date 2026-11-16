import { useRef, useState, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { errorMessage } from '../api/client'
import { authApi, resumesApi } from '../api/endpoints'
import { queryKeys } from '../api/queryKeys'
import type { Resume } from '../api/types'
import { useCurrentUser } from '../auth/AuthProvider'
import { ErrorState } from '../components/Feedback'
import { useNotify } from '../components/Notices'
import {
  Button, CountUp, EvidenceLadder, FileDropzone, IconButton, LoadingRegion, PageHeader, Panel, Skeleton, Spinner, buttonProps,
} from '../design/components'
import {
  ArrowRight, BadgeCheck, CalendarClock, Check, ChevronRight, Clock, FileText, Icon, ListChecks, X, type IconComponent,
} from '../design/icons'
import { TIERS, tierOrdinal } from '../design/lib/labels'
import { useInView } from '../design/motion'
import { formatDate, plural, relativeTime } from '../design/lib/format'
import { timeRange } from '../lib/calendar'
import { interviewEnd, nextStepFor, tasksForJob } from '../lib/nextStep'
import {
  interviewsQuery, latestResume, ledgerQuery, recentApplicationsQuery, resumesQuery, sourcesQuery, workSamplesQuery,
} from '../lib/queries'
import { RESUME_REVIEWED_KEY, readStored, writeStored } from '../lib/storage'
import { ApplicationStatusBadge, NextStepText } from './ApplicationsPage'
import { POLL_INTERVAL_MS } from './resumes/useResumeAnalysis'
import './common.css'
import './home/home.css'

const HIDE_KEY = 'onboarding-checklist-hidden'

/**
 * Overview (FLOWS-CANDIDATE §11): the getting-started checklist until it is done, then "Needs your attention"
 * (tasks, interviews, results to review), recent applications with their next step, and the evidence ladder.
 */
export function HomePage() {
  const user = useCurrentUser()
  const resumes = useQuery(resumesQuery)
  const sources = useQuery(sourcesQuery)
  const ledger = useQuery(ledgerQuery)
  const applications = useQuery(recentApplicationsQuery)
  // Started here so the attention list and next steps arrive together.
  useQuery(workSamplesQuery)
  useQuery(interviewsQuery)

  // A new account sees the checklist alone; the rest of the overview appears once there is something to show.
  const settled = user.emailVerified && (resumes.data?.length ?? 0) > 0 && (sources.data?.length ?? 0) > 0 && (applications.data?.totalElements ?? 0) > 0
  // Sources decide the "Strengthen" step, so the checklist waits for them too (it flashed that step as current).
  const loading = resumes.isPending || applications.isPending || ledger.isPending || sources.isPending
  // A failed load must not look like a brand-new account (the checklist would ask for a resume the candidate has).
  const failed = [resumes, sources, applications, ledger].find(q => q.isError && q.data === undefined)
  const active = (applications.data?.content ?? []).filter(a => ['SUBMITTED', 'SHORTLISTED', 'INTERVIEWING'].includes(a.status)).length

  return (
    <div className="h-page">
      <PageHeader
        title={`Welcome${applications.data?.totalElements ? ' back' : ''}, ${user.firstName}`}
        documentTitle="Overview"
        meta={
          applications.data
            ? [active ? `${plural(active, 'application')} in progress` : 'No applications in progress', formatDate(new Date(), { weekday: 'long', day: 'numeric', month: 'long' })]
            : undefined
        }
      />
      {loading ? (
        <LoadingRegion label="Loading your overview">
          <Skeleton width="100%" height={180} style={{ borderRadius: 6 }} />
          <div className="h-skel-cols">
            <Skeleton width="100%" height={120} />
            <Skeleton width="100%" height={120} />
          </div>
        </LoadingRegion>
      ) : failed ? (
        <ErrorState
          error={failed.error}
          title="Your overview could not be loaded"
          onRetry={() => {
            for (const q of [resumes, sources, applications, ledger]) if (q.isError) void q.refetch()
          }}
        />
      ) : (
        <>
          <Checklist
            emailVerified={user.emailVerified}
            email={user.email}
            resumes={resumes.data ?? []}
            hasSources={(sources.data?.length ?? 0) > 0}
            hasApplications={(applications.data?.totalElements ?? 0) > 0}
          />
          <Attention quietWhenEmpty={!settled} />
          {settled || (applications.data?.totalElements ?? 0) > 0 || (ledger.data?.length ?? 0) > 0 ? (
            <div className="h-cols">
              <RecentApplications />
              <EvidenceSummary />
            </div>
          ) : null}
        </>
      )}
    </div>
  )
}

// ------------------------------------------------------------------------------------------------- checklist

interface Step {
  key: string
  title: string
  done: boolean
  description: ReactNode
  action?: ReactNode
}

function Checklist({ emailVerified, email, resumes, hasSources, hasApplications }: { emailVerified: boolean; email: string; resumes: Resume[]; hasSources: boolean; hasApplications: boolean }) {
  const queryClient = useQueryClient()
  const notify = useNotify()
  const [hidden, setHidden] = useState(() => readStored(HIDE_KEY) === '1')
  const latest = latestResume(resumes)
  const analysis = useQuery({
    queryKey: queryKeys.resumeAnalysis(latest?.id ?? 0),
    queryFn: () => resumesApi.analysis(latest!.id),
    enabled: !!latest,
    // While the resume is being read, check again so the next step opens by itself (about 2 minutes at most).
    refetchInterval: q =>
      (q.state.data?.status === 'PENDING' || q.state.data?.status === 'PROCESSING') && q.state.dataUpdateCount < 40 ? POLL_INTERVAL_MS : false,
  })
  const analysed = analysis.data?.status === 'COMPLETED' || analysis.data?.status === 'PARTIAL'
  const reading = analysis.data?.status === 'PENDING' || analysis.data?.status === 'PROCESSING'

  const upload = useMutation({
    mutationFn: (file: File) => resumesApi.upload(file),
    onSuccess: resume => {
      queryClient.setQueryData<Resume[]>(queryKeys.resumes, current => [resume, ...(current ?? [])])
      void queryClient.invalidateQueries({ queryKey: queryKeys.resumes })
      notify('success', `${resume.originalFilename} uploaded`, { body: 'We are reading it now. The next step opens when it is ready.' })
    },
  })
  const resend = useMutation({
    mutationFn: authApi.resendVerification,
    onSuccess: () => notify('success', 'Confirmation link sent', { body: `Check ${email}.` }),
    onError: error => notify('error', errorMessage(error)),
  })

  const steps: Step[] = [
    {
      key: 'resume',
      title: 'Add your resume',
      done: resumes.length > 0,
      description: 'Employers need one to review you. We read it and show you exactly what we found.',
      action: (
        <FileDropzone
          compact
          prompt="Drop a PDF, DOC or DOCX here, or"
          hint="Up to 10 MB. It uploads straight away."
          accept=".pdf,.doc,.docx"
          maxSize={10 * 1024 * 1024}
          disabled={upload.isPending}
          onFiles={files => upload.mutate(files[0])}
          files={upload.isPending && upload.variables ? [{ id: 'up', name: upload.variables.name, size: upload.variables.size, progress: 0.6 }] : []}
          error={upload.error ? errorMessage(upload.error) : undefined}
        />
      ),
    },
    {
      key: 'review',
      title: 'Review what we read',
      done: analysed && readStored(RESUME_REVIEWED_KEY) === '1',
      description: reading ? (
        <span className="h-inline">
          <Spinner size={12} /> We are reading your resume. This usually takes a few seconds.
        </span>
      ) : (
        'Check the skills and experience we found, and correct anything that is off. Corrections become your claims.'
      ),
      action: !reading && (
        <Link {...buttonProps('primary')} to="/resumes">
          Review what we read
          <Icon icon={ArrowRight} size={16} />
        </Link>
      ),
    },
    {
      key: 'strengthen',
      title: 'Strengthen with a source',
      done: hasSources,
      description: 'Connect GitHub, your website or a certificate. Checkers compare your claims with it, and nothing is shared until you choose.',
      action: (
        <Link {...buttonProps('primary')} to="/verification?add=source&step=choose">
          Add a source
          <Icon icon={ArrowRight} size={16} />
        </Link>
      ),
    },
    {
      key: 'apply',
      title: 'Apply to a role',
      done: hasApplications,
      description: 'One dialog: your resume is preselected and you see the evidence that goes with it.',
      action: (
        <Link {...buttonProps('primary')} to="/jobs">
          Find jobs
          <Icon icon={ArrowRight} size={16} />
        </Link>
      ),
    },
  ]
  const all: Step[] = [
    {
      key: 'email',
      title: 'Confirm your email',
      done: emailVerified,
      description: `We sent a link to ${email}. Some employers accept applications only from confirmed addresses.`,
      action: (
        <Button size="sm" loading={resend.isPending} onClick={() => resend.mutate()}>
          Resend link
        </Button>
      ),
    },
    ...steps,
  ]
  const doneCount = all.filter(s => s.done).length
  const current = steps.find(s => !s.done)?.key ?? (emailVerified ? null : 'email')

  if (hidden || doneCount === all.length) return null

  function hide() {
    writeStored(HIDE_KEY, '1')
    setHidden(true)
    notify('info', 'Checklist hidden', {
      action: {
        label: 'Undo',
        onClick: () => {
          writeStored(HIDE_KEY, null)
          setHidden(false)
        },
      },
    })
  }

  return (
    <Panel className="h-check" aria-labelledby="h-check-title">
      <div className="h-check-head">
        <div>
          <h2 id="h-check-title" className="t-h2">
            Get set up
          </h2>
          <p className="c-muted">
            <span className="numeric">
              {doneCount} of {all.length}
            </span>{' '}
            done. Each step makes your applications stronger.
          </p>
        </div>
        <IconButton label="Hide checklist" icon={X} size="sm" onClick={hide} />
      </div>
      <div className="h-check-bar" aria-hidden="true">
        {all.map((s, i) => (
          <span key={s.key} data-done={s.done || undefined} style={{ ['--i' as string]: i }} />
        ))}
      </div>
      <ol className="h-steps">
        {all.map((s, i) => {
          const isCurrent = s.key === current
          return (
            <li key={s.key} className="h-step" data-done={s.done || undefined} data-current={isCurrent || undefined} aria-current={isCurrent ? 'step' : undefined}>
              <span className="h-step-mark" aria-hidden="true">
                {s.done ? <Icon icon={Check} size={12} strokeWidth={2.25} /> : <span className="numeric">{i + 1}</span>}
              </span>
              <div className="h-step-body">
                <div className="h-step-title">
                  <span>
                    {s.title}
                    {s.done && <span className="sr-only"> (done)</span>}
                  </span>
                  {/* Confirming the email never blocks the next step: its action sits on the title line. */}
                  {!s.done && !isCurrent && s.key === 'email' && s.action}
                </div>
                {!s.done && isCurrent && (
                  <div className="h-step-detail">
                    <p className="c-muted">{s.description}</p>
                    {s.action && <div className="h-step-action">{s.action}</div>}
                  </div>
                )}
              </div>
            </li>
          )
        })}
      </ol>
    </Panel>
  )
}

// ------------------------------------------------------------------------------------------------- attention

interface AttentionItem {
  key: string
  icon: IconComponent
  title: ReactNode
  meta: ReactNode
  to: string
  action: string
  urgency: number
}

function Attention({ quietWhenEmpty }: { quietWhenEmpty: boolean }) {
  const samples = useQuery(workSamplesQuery)
  const interviews = useQuery(interviewsQuery)
  const ledger = useQuery(ledgerQuery)
  const now = Date.now()
  const items: AttentionItem[] = []

  for (const s of samples.data ?? []) {
    if (s.status === 'STARTED') {
      items.push({ key: `ws${s.assignmentId}`, icon: Clock, title: `Finish “${s.title}”`, meta: `${s.jobTitle} · timer running${s.deadlineAt ? `, ends ${relativeTime(s.deadlineAt)}` : ''}`, to: `/work-samples/${s.assignmentId}`, action: 'Continue', urgency: 0 })
    } else if (s.status === 'ASSIGNED') {
      items.push({
        key: `ws${s.assignmentId}`,
        icon: ListChecks,
        title: `Work sample: ${s.title}`,
        meta: `${s.jobTitle} · ${s.timeLimitMinutes} minutes${s.dueAt ? ` · start by ${formatDate(s.dueAt, { weekday: 'short', day: 'numeric', month: 'short' })} (${relativeTime(s.dueAt)})` : ''}`,
        to: `/work-samples/${s.assignmentId}`,
        action: 'Open',
        urgency: s.dueAt ? 1 + (new Date(s.dueAt).getTime() - now) / 864e5 / 100 : 2,
      })
    }
  }
  for (const i of interviews.data ?? []) {
    if (i.status !== 'SCHEDULED' || interviewEnd(i) < now) continue
    const r = timeRange(i.scheduledAt, i.durationMinutes)
    items.push({ key: `iv${i.id}`, icon: CalendarClock, title: `Interview: ${i.jobTitle}`, meta: `${r.day}, ${r.time} · ${relativeTime(i.scheduledAt)}`, to: '/interviews', action: 'Details', urgency: 1.5 })
  }
  const awaiting = (ledger.data ?? []).reduce((n, c) => n + c.verdicts.filter(v => v.candidateStatus === 'AWAITING_CANDIDATE').length, 0)
  if (awaiting > 0) {
    items.push({ key: 'review', icon: BadgeCheck, title: `${plural(awaiting, 'verification result')} waiting for your review`, meta: 'Only you can see them until you share.', to: '/verification?review=first', action: 'Review', urgency: 3 })
  }
  items.sort((a, b) => a.urgency - b.urgency)
  if (items.length === 0 && quietWhenEmpty) return null

  return (
    <section className="h-section" aria-labelledby="h-attention">
      <h2 id="h-attention" className="t-h2">
        Needs your attention
      </h2>
      {items.length === 0 ? (
        <p className="h-calm">
          <Icon icon={Check} size={16} /> Nothing needs you right now. We will notify you when an employer moves forward.
        </p>
      ) : (
        <ul className="c-rows" data-closed="">
          {items.map(item => (
            <li key={item.key} className="c-linkrow h-att">
              <span className="h-att-icon">
                <Icon icon={item.icon} size={16} />
              </span>
              <div className="h-att-main">
                <Link to={item.to} className="c-linkrow-link t-strong">
                  {item.title}
                </Link>
                <span className="c-meta">{item.meta}</span>
              </div>
              <span className="h-att-action">
                {item.action}
                <Icon icon={ChevronRight} size={16} />
              </span>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}

// ------------------------------------------------------------------------------------------------- applications

function RecentApplications() {
  const applications = useQuery(recentApplicationsQuery)
  const samples = useQuery(workSamplesQuery)
  const interviews = useQuery(interviewsQuery)
  const recent = [...(applications.data?.content ?? [])].sort((a, b) => b.updatedAt.localeCompare(a.updatedAt)).slice(0, 3)

  return (
    <section className="h-section" aria-labelledby="h-apps">
      <div className="h-section-head">
        <h2 id="h-apps" className="t-h2">
          Your applications
        </h2>
        {recent.length > 0 && (
          <Link to="/applications" className="h-more">
            All applications
          </Link>
        )}
      </div>
      {recent.length === 0 ? (
        <div className="h-empty">
          <Icon icon={FileText} size={20} />
          <div>
            <p className="t-strong">No applications yet</p>
            <p className="c-muted">Each one appears here with its next step.</p>
            <Link {...buttonProps('secondary', 'sm', 'h-empty-action')} to="/jobs">
              Find jobs
            </Link>
          </div>
        </div>
      ) : (
        <ul className="c-rows" data-closed="">
          {recent.map(a => {
            const tasks = tasksForJob(a.jobTitle, samples.data, interviews.data)
            return (
              <li key={a.id} className="c-linkrow h-app">
                <div className="h-app-main">
                  <Link to={`/applications/${a.id}`} className="c-linkrow-link t-strong">
                    {a.jobTitle}
                  </Link>
                  <span className="c-meta">{a.organizationName}</span>
                </div>
                <ApplicationStatusBadge status={a.status} />
                <span className="h-app-next">
                  <NextStepText step={nextStepFor(a, tasks.samples, tasks.interviews)} />
                </span>
              </li>
            )
          })}
        </ul>
      )}
    </section>
  )
}

// ------------------------------------------------------------------------------------------------- evidence

function EvidenceSummary() {
  const ledger = useQuery(ledgerQuery)
  const claims = ledger.data ?? []
  const counts = TIERS.map(t => claims.filter(c => c.tier === t).length)
  const max = Math.max(1, ...counts)
  const shared = claims.reduce((n, c) => n + c.verdicts.filter(v => v.candidateStatus === 'SHARED').length, 0)
  const top = [...TIERS].reverse()
  // Bars and numbers start together, the first time the block scrolls into view (M4).
  const ref = useRef<HTMLUListElement>(null)
  const seen = useInView(ref)

  return (
    <section className="h-section" aria-labelledby="h-evidence">
      <div className="h-section-head">
        <h2 id="h-evidence" className="t-h2">
          Your evidence
        </h2>
        <Link to="/verification" className="h-more">
          Strengthen
        </Link>
      </div>
      {claims.length === 0 ? (
        <p className="c-muted">Your resume's skills and your claims appear here, sorted by how strongly they are evidenced.</p>
      ) : (
        <>
          <ul ref={ref} className="h-ladder" data-seen={seen || undefined} aria-label="Claims by evidence level">
            {top.map(t => {
              const n = counts[TIERS.indexOf(t)]
              return (
                <li key={t} data-empty={n === 0 || undefined}>
                  <EvidenceLadder tier={t} audience="candidate" animate={false} />
                  <span className="h-ladder-track" aria-hidden="true">
                    <span style={{ ['--v' as string]: n / max, ['--i' as string]: tierOrdinal(t) }} />
                  </span>
                  <span className="h-ladder-n">
                    <CountUp value={n} />
                  </span>
                </li>
              )
            })}
          </ul>
          <p className="c-meta h-shared">
            {plural(claims.length, 'claim')} in total · {plural(shared, 'verification result')} shared with employers
          </p>
        </>
      )}
    </section>
  )
}
