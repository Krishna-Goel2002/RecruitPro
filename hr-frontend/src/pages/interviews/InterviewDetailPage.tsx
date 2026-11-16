import { useMemo, useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useParams } from 'react-router-dom'
import { interviewsApi } from '../../api/assessments'
import { qk } from '../../api/queryKeys'
import { RECOMMENDATIONS } from '../../api/types'
import type { InterviewDetail, Rating, Recommendation, Scorecard, ScorecardInput } from '../../api/types'
import { useAuth, useCurrentUser } from '../../auth/AuthContext'
import { ActionDialog } from '../../components/ActionDialog'
import { AnchoredRating } from '../../components/AnchoredRating'
import { InlineError, QueryError } from '../../components/QueryError'
import { SaveIndicator } from '../../components/SaveIndicator'
import {
  Badge, Button, EmptyState, EvidenceLadder, Field, LoadingRegion, PageHeader, ReqMarker, SegmentedControl, Skeleton, SkeletonText, Textarea, buttonProps, useToast,
} from '../../design/components'
import { ArrowUpRight, CalendarClock, FileText, Icon, Pencil } from '../../design/icons'
import { safeHttpUrl } from '../../lib/format'
import { readDraft, useDraftAutosave } from '../../lib/localDraft'
import { slotLabel, timeZoneLabel } from '../../lib/when'
import { ScorecardView } from './ScorecardView'
import './interviews.css'

const REC_WORD: Record<Recommendation, string> = { STRONG_NO: 'Strong no', NO: 'No', YES: 'Yes', STRONG_YES: 'Strong yes' }

/** /interviews/:id — kit questions with inline anchored ratings, focus requirements and the scorecard (FLOWS-HR-B §3). */
export function InterviewDetailPage() {
  const id = Number(useParams().interviewId)
  const query = useQuery({ queryKey: qk.interview(id), queryFn: ({ signal }) => interviewsApi.detail(id, signal) })
  if (query.isPending) {
    return (
      <div className="iv" data-width="wide">
        <LoadingRegion label="Loading interview">
          <div className="ap-skel-head"><Skeleton width={160} height={10} /><Skeleton width={280} height={22} /><Skeleton width={320} height={10} /></div>
          <div className="iv-skel">{Array.from({ length: 3 }, (_, i) => <div key={i} className="iv-q-skel"><SkeletonText lines={2} /><div className="iv-skel-cards">{Array.from({ length: 4 }, (_, j) => <Skeleton key={j} height={64} />)}</div></div>)}</div>
        </LoadingRegion>
      </div>
    )
  }
  if (query.isError) {
    return (
      <div className="iv" data-width="wide">
        <PageHeader title="Interview" breadcrumb={[{ label: 'My interviews', to: '/interviews' }]} />
        <QueryError error={query.error} title="We couldn’t load this interview" onRetry={() => void query.refetch()} retrying={query.isFetching} />
      </div>
    )
  }
  return <InterviewView interview={query.data} />
}

/** Why the assigned interviewer can no longer submit or edit the scorecard. */
function scorecardLockedNote(interview: InterviewDetail): string {
  const status = interview.applicationStatus
  if (status === 'WITHDRAWN') return 'The candidate withdrew the application, so the scorecard is closed.'
  if (status === 'SELECTED' || status === 'REJECTED') return `The application was ${status === 'SELECTED' ? 'selected' : 'rejected'}, so the scorecard is locked. Earlier versions are kept for the audit trail.`
  return 'The scorecard can no longer be changed for this interview.'
}

function InterviewView({ interview }: { interview: InterviewDetail }) {
  const user = useCurrentUser()
  const { can } = useAuth()
  const mine = interview.interviewerUserId === user.id
  // The server says whether the scorecard is still open (R6-B4); it is still only the assigned interviewer's to write.
  const locked = interview.scorecardEditable === false
  const canScore = mine && interview.status === 'SCHEDULED' && !interview.scorecard && !locked
  const canEdit = mine && interview.scorecard != null && interview.status !== 'CANCELLED' && !locked
  const lockedNote = mine && locked && interview.status !== 'CANCELLED' ? scorecardLockedNote(interview) : null
  const [editing, setEditing] = useState(false)
  const link = safeHttpUrl(interview.location)
  const start = new Date(interview.scheduledAt).getTime()
  const live = Date.now() > start - 15 * 60_000 && Date.now() < start + interview.durationMinutes * 60_000

  return (
    <div className="iv" data-width="wide">
      <PageHeader
        breadcrumb={[{ label: 'My interviews', to: '/interviews' }, { label: interview.jobTitle }]}
        title={interview.candidateName}
        documentTitle={`Interview · ${interview.candidateName}`}
        status={
          interview.scorecard ? <Badge tone="success">Scorecard in</Badge>
            : interview.status === 'CANCELLED' ? <Badge tone="neutral" variant="outline">Cancelled</Badge>
              : locked ? <Badge tone="neutral" variant="outline">Scorecard closed</Badge>
                : start < Date.now() ? <Badge tone="warning">Awaiting scorecard</Badge> : <Badge tone="info">Scheduled</Badge>
        }
        meta={[
          <span key="t" className="numeric">{slotLabel(interview.scheduledAt, interview.durationMinutes)}</span>,
          <span key="z">{timeZoneLabel()}</span>,
          <span key="i">Interviewer: {mine ? 'you' : interview.interviewerName}</span>,
          interview.location && !link ? <span key="l">{interview.location}</span> : null,
        ]}
        actions={<>
          {can('jobs.view') && <Link {...buttonProps('secondary')} to={`/jobs/${interview.jobId}/applicants/${interview.applicationId}`}>Open the application</Link>}
          {link && interview.status === 'SCHEDULED' && (
            <a {...buttonProps(live ? 'primary' : 'secondary')} href={link} target="_blank" rel="noopener noreferrer nofollow">
              Join call <Icon icon={ArrowUpRight} size={14} /><span className="sr-only"> (opens in a new tab)</span>
            </a>
          )}
        </>}
      />

      <div className="iv-layout">
        <div className="iv-main">
          {canScore || (canEdit && editing) ? (
            <ScorecardForm key={editing ? 'edit' : 'new'} interview={interview} existing={editing ? interview.scorecard : null} onDone={() => setEditing(false)} onCancel={editing ? () => setEditing(false) : undefined} />
          ) : interview.scorecard ? (
            <section aria-labelledby="iv-sc-h" className="iv-section">
              <div className="iv-section-head">
                <h2 id="iv-sc-h" className="t-h2">Scorecard</h2>
                {canEdit && <Button icon={Pencil} variant="secondary" onClick={() => setEditing(true)}>Edit scorecard</Button>}
              </div>
              <ScorecardView scorecard={interview.scorecard} questions={interview.kit} interviewId={interview.id} canSeeRevisions={mine || user.role === 'ADMIN' || user.role === 'HR_MANAGER'} />
              {canEdit && <p className="iv-fine">You can edit it until the application is decided. Earlier versions are kept for the audit trail.</p>}
              {lockedNote && <p className="iv-fine">{lockedNote}</p>}
            </section>
          ) : (
            <section aria-labelledby="iv-kit-h" className="iv-section">
              <div className="iv-section-head">
                <h2 id="iv-kit-h" className="t-h2">Questions</h2>
                <p className="iv-fine">{interview.status === 'CANCELLED' ? 'This interview was cancelled.' : lockedNote ? lockedNote : mine ? '' : `${interview.interviewerName} has not submitted a scorecard yet.`}</p>
              </div>
              {interview.kit.length === 0 ? <EmptyState icon={FileText} title="No interview kit yet" description="An HR manager can generate one on the job’s Interview kit tab." /> : (
                <ol className="iv-questions">
                  {interview.kit.map((q, i) => (
                    <li key={q.id} className="iv-q">
                      <p className="iv-q-text"><span className="iv-q-n numeric">{i + 1}</span>{q.question}</p>
                      {q.followUps.length > 0 && <ul className="iv-follow">{q.followUps.map((f, j) => <li key={j}>{f}</li>)}</ul>}
                      <AnchoredRating legend={`Anchors for question ${i + 1}`} hideLegend anchors={q.anchors} value={null} onChange={() => undefined} disabled />
                    </li>
                  ))}
                </ol>
              )}
            </section>
          )}
        </div>
        <aside className="iv-aside" aria-label="Focus requirements">
          <h2 className="t-h3">Focus on these requirements</h2>
          {interview.focusRequirements.length === 0 ? <p className="iv-fine">Every requirement already has resume-level evidence or better.</p> : (
            <>
              <p className="iv-fine">Evidence so far is weak or only claimed. Probe these with the questions.</p>
              <ul className="iv-focus">
                {interview.focusRequirements.map(r => (
                  <li key={r.requirementId}>
                    <ReqMarker kind={r.mandatory ? 'must' : 'nice'} />
                    <span className="iv-focus-name">{r.description}</span>
                    <EvidenceLadder tier={r.tier} audience="staff" animate={false} />
                    {r.verificationHint && <span className="iv-focus-hint">{r.verificationHint}</span>}
                  </li>
                ))}
              </ul>
            </>
          )}
          <p className="iv-fine iv-aside-foot"><Icon icon={CalendarClock} size={12} /> Ratings of 3 or 4 on requirement-linked questions count as interview-verified evidence.</p>
        </aside>
      </div>
    </div>
  )
}

type Draft = { ratings: Record<string, Rating | null>; notes: Record<string, string>; recommendation: Recommendation | null; summary: string }

/**
 * Scorecard form: each question card carries its own anchored rating and notes, so the interviewer never scrolls
 * between question and form. The draft autosaves on this device and comes back after a reload; Submit confirms.
 */
function ScorecardForm({ interview, existing, onDone, onCancel }: { interview: InterviewDetail; existing: Scorecard | null; onDone: () => void; onCancel?: () => void }) {
  const queryClient = useQueryClient()
  const { toast } = useToast()
  const editing = existing != null
  const { user } = useAuth()
  const key = `scorecard:${user?.id ?? 'anon'}:${interview.id}${editing ? ':edit' : ''}`
  const initial = useMemo<Draft>(() => {
    const saved = readDraft<Draft>(key)?.value
    // A stored draft is used only if it has the expected shape (another app version could have written it).
    if (saved && typeof saved === 'object' && saved.ratings && typeof saved.ratings === 'object' && saved.notes && typeof saved.notes === 'object') {
      return { ratings: saved.ratings, notes: saved.notes, recommendation: saved.recommendation ?? null, summary: typeof saved.summary === 'string' ? saved.summary : '' }
    }
    return {
      ratings: Object.fromEntries(interview.kit.map(q => [q.id, existing?.ratings.find(r => r.questionId === q.id)?.rating ?? null])),
      notes: Object.fromEntries((existing?.ratings ?? []).filter(r => r.notes).map(r => [r.questionId, r.notes as string])),
      recommendation: existing?.recommendation ?? null,
      summary: existing?.summary ?? '',
    }
  }, [key, interview.kit, existing])
  const [draft, setDraft] = useState<Draft>(initial)
  const [touched, setTouched] = useState(false)
  const [confirm, setConfirm] = useState(false)
  const autosave = useDraftAutosave(key, draft)
  const rated = interview.kit.filter(q => draft.ratings[q.id] != null).length
  const missing = rated < interview.kit.length || draft.recommendation == null

  const submit = useMutation({
    mutationFn: (body: ScorecardInput) => (editing ? interviewsApi.updateScorecard(interview.id, body) : interviewsApi.submitScorecard(interview.id, body)),
    onSuccess: () => {
      autosave.clear()
      setConfirm(false)
      toast({ tone: 'success', title: editing ? 'Scorecard updated' : 'Scorecard submitted', body: editing ? 'The previous version is kept in the history.' : 'Thank you. The hiring team can read it now.' })
      void queryClient.invalidateQueries({ queryKey: qk.interviews })
      void queryClient.invalidateQueries({ queryKey: ['applicants', interview.jobId] })
      onDone()
    },
  })

  function onSubmit(event: FormEvent) {
    event.preventDefault()
    setTouched(true)
    if (missing) {
      const first = interview.kit.find(q => draft.ratings[q.id] == null)
      const target = first ? document.querySelector<HTMLInputElement>(`#q-${first.id} input`) : document.querySelector<HTMLInputElement>('#iv-rec input')
      target?.focus()
      target?.scrollIntoView({ block: 'center', behavior: 'smooth' })
      return
    }
    setConfirm(true)
  }

  function send() {
    submit.mutate({
      ratings: interview.kit.map(q => ({ questionId: q.id, requirementId: q.requirementId, rating: draft.ratings[q.id] as Rating, notes: draft.notes[q.id]?.trim() || null })),
      recommendation: draft.recommendation as Recommendation,
      summary: draft.summary.trim() || null,
    })
  }

  return (
    <section aria-labelledby="iv-form-h" className="iv-section">
      <div className="iv-section-head">
        <h2 id="iv-form-h" className="t-h2">{editing ? 'Edit your scorecard' : 'Questions and scorecard'}</h2>
        <SaveIndicator state={autosave.savedAt ? { kind: 'saved', at: autosave.savedAt, where: 'device' } : { kind: 'idle' }} />
      </div>
      <p className="iv-fine">Ask each question, use the follow-ups as needed, and rate the answer against the anchors. Tip: with a question focused, press 1–4.</p>
      <form className="iv-form" onSubmit={onSubmit} noValidate>
        {interview.kit.length === 0 && <EmptyState compact icon={FileText} title="This job has no interview kit" description="Give a recommendation and a summary below." />}
        <ol className="iv-questions">
          {interview.kit.map((q, i) => (
            <li key={q.id} id={`q-${q.id}`} className="iv-q" data-rated={draft.ratings[q.id] != null || undefined}>
              <p className="iv-q-text"><span className="iv-q-n numeric">{i + 1}</span>{q.question}</p>
              {q.followUps.length > 0 && (
                <details className="iv-follow-d">
                  <summary>{q.followUps.length === 1 ? '1 follow-up' : `${q.followUps.length} follow-ups`}</summary>
                  <ul className="iv-follow">{q.followUps.map((f, j) => <li key={j}>{f}</li>)}</ul>
                </details>
              )}
              <AnchoredRating
                legend={`Rating for question ${i + 1}`}
                hideLegend
                anchors={q.anchors}
                value={draft.ratings[q.id] ?? null}
                required
                invalid={touched && draft.ratings[q.id] == null}
                onChange={v => setDraft(d => ({ ...d, ratings: { ...d.ratings, [q.id]: v } }))}
              />
              <Field label="Notes" optional>
                <Textarea rows={1} maxRows={6} maxLength={2000} value={draft.notes[q.id] ?? ''} onChange={e => setDraft(d => ({ ...d, notes: { ...d.notes, [q.id]: e.target.value } }))} />
              </Field>
            </li>
          ))}
        </ol>

        <div className="iv-final">
          <div id="iv-rec" className="iv-rec">
            <p className="t-label" aria-hidden="true">Overall recommendation</p>
            <SegmentedControl
              label="Overall recommendation"
              value={draft.recommendation}
              onChange={v => setDraft(d => ({ ...d, recommendation: v as Recommendation }))}
              options={RECOMMENDATIONS.map(r => ({ value: r, label: REC_WORD[r] }))}
              block
            />
            {touched && draft.recommendation == null && <InlineError error="Choose a recommendation." />}
          </div>
          <Field label="Summary" optional>
            <Textarea rows={3} maxRows={10} maxLength={4000} showCount value={draft.summary} placeholder="Strengths, concerns and anything the next interviewer should probe." onChange={e => setDraft(d => ({ ...d, summary: e.target.value }))} />
          </Field>
        </div>
        <InlineError error={submit.error} />

        <div className="iv-foot">
          <p className="ap-progress">
            <span className="ap-progress-bar" aria-hidden="true"><i style={{ ['--v' as string]: interview.kit.length ? rated / interview.kit.length : 1 }} /></span>
            <span><strong className="numeric">{rated}</strong> of <span className="numeric">{interview.kit.length}</span> rated{draft.recommendation ? <> · {REC_WORD[draft.recommendation]}</> : null}</span>
          </p>
          <div className="ap-foot-actions">
            {onCancel && <Button variant="secondary" onClick={() => { autosave.clear(); onCancel() }} disabled={submit.isPending}>Cancel</Button>}
            <Button type="submit" variant="primary">{editing ? 'Save changes' : 'Submit scorecard'}</Button>
          </div>
        </div>
      </form>

      <ActionDialog
        open={confirm}
        title={editing ? 'Save your changes?' : 'Submit your scorecard?'}
        description={`Recommendation: ${draft.recommendation ? REC_WORD[draft.recommendation] : '—'}. Ratings of 3 or 4 on requirement-linked questions count as interview-verified evidence. ${editing ? 'The previous version is kept and the change is audited.' : 'You can edit it until the application is decided.'}`}
        confirmLabel={editing ? 'Save changes' : 'Submit scorecard'}
        busy={submit.isPending}
        error={submit.error}
        onCancel={() => setConfirm(false)}
        onConfirm={send}
      />
    </section>
  )
}
