import { useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { interviewsApi } from '../../api/assessments'
import { isApiError } from '../../api/client'
import { qk } from '../../api/queryKeys'
import { reviewApi } from '../../api/review'
import type { ApplicantDetail, Interview, UpdateInterviewRequest } from '../../api/types'
import { useAuth, useCurrentUser } from '../../auth/AuthContext'
import { ActionDialog } from '../../components/ActionDialog'
import { InlineError } from '../../components/QueryError'
import {
  Avatar, Badge, Button, Dialog, EmptyState, Field, IconButton, Input, Menu, SegmentedControl, Select, buttonProps, useToast,
} from '../../design/components'
import { Building2, CalendarClock, ChevronDown, Ellipsis, Icon, Link2 } from '../../design/icons'
import { fromLocalInput, roleLabel, safeHttpUrl, toLocalInput } from '../../lib/format'
import { fullDateTime, slotLabel, timeZoneLabel } from '../../lib/when'
import { ScorecardView } from '../interviews/ScorecardView'
import { recommendationWord } from './DecisionBar'

const SCHEDULABLE = ['SHORTLISTED', 'INTERVIEWING']
const MIN = 5
const MAX = 480
const QUICK = ['30', '45', '60', '90'] as const

/** Interviews on the applicant (FLOWS-HR-B §3): schedule, reschedule, cancel, read scorecards inline. */
export function InterviewsTab({ jobId, detail, onSchedule }: { jobId: number; detail: ApplicantDetail; onSchedule: () => void }) {
  const { can } = useAuth()
  const me = useCurrentUser()
  const queryClient = useQueryClient()
  const { toast } = useToast()
  const [rescheduling, setRescheduling] = useState<Interview | null>(null)
  const [cancelling, setCancelling] = useState<Interview | null>(null)
  const [openCard, setOpenCard] = useState<number | null>(null)
  const kit = useQuery({
    queryKey: qk.interviewKit(jobId),
    queryFn: async ({ signal }) => {
      try { return await interviewsApi.kit(jobId, signal) } catch (cause) { if (isApiError(cause, 404)) return null; throw cause }
    },
    enabled: detail.interviews.some(i => i.scorecard),
  })
  const canSchedule = can('interviews.schedule') && SCHEDULABLE.includes(detail.applicationStatus)
  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: qk.applicant(jobId, detail.applicationId) })
    void queryClient.invalidateQueries({ queryKey: qk.applicants(jobId) })
    void queryClient.invalidateQueries({ queryKey: qk.interviews })
  }
  const cancel = useMutation({
    mutationFn: (i: Interview) => interviewsApi.update(i.id, { status: 'CANCELLED' }),
    onSuccess: () => { setCancelling(null); toast({ tone: 'success', title: 'Interview cancelled', body: 'The interviewer and the candidate are notified.' }); refresh() },
  })
  const sorted = [...detail.interviews].sort((a, b) => a.scheduledAt.localeCompare(b.scheduledAt))

  return (
    <div className="ap-iv">
      <div className="ap-tab-head">
        <p className="ap-tab-intro">Structured interviews use the job’s kit. A rating of 3 or 4 on a requirement-linked question marks it <strong>Interview-verified</strong>.</p>
        {can('interviews.schedule') && (
          <div className="ap-tab-actions">
            <Button icon={CalendarClock} onClick={onSchedule} disabled={!canSchedule}>Schedule interview</Button>
          </div>
        )}
      </div>
      {can('interviews.schedule') && !canSchedule && <p className="ap-muted ap-hint-line">Interviews can be scheduled once the application is shortlisted. Scheduling the first one moves it to Interviewing.</p>}

      {sorted.length === 0 ? (
        <EmptyState icon={CalendarClock} title="No interviews yet" description="Schedule one with a kit-trained interviewer; both of you are notified." />
      ) : (
        <ul className="ap-rows">
          {sorted.map(i => {
            const link = safeHttpUrl(i.location)
            const past = new Date(i.scheduledAt).getTime() < Date.now()
            const open = openCard === i.id
            return (
              <li key={i.id} className="ap-row" data-open={open || undefined} data-cancelled={i.status === 'CANCELLED' || undefined}>
                <div className="ap-row-head">
                  <div className="ap-row-title">
                    <span className="ap-row-name numeric">{slotLabel(i.scheduledAt, i.durationMinutes)}</span>
                    <span className="ap-row-meta ap-iv-meta">
                      <Avatar name={i.interviewerName} size={24} />
                      <span>{i.interviewerUserId === me.id ? 'You' : i.interviewerName}</span>
                      {i.location && (link ? (
                        <a href={link} target="_blank" rel="noopener noreferrer nofollow"><Icon icon={Link2} size={12} /> Join link<span className="sr-only"> (opens in a new tab)</span></a>
                      ) : <span><Icon icon={Building2} size={12} /> {i.location}</span>)}
                    </span>
                  </div>
                  <div className="ap-row-status">
                    {i.scorecard ? <Badge tone="success">Scorecard in</Badge>
                      : i.status === 'CANCELLED' ? <Badge tone="neutral" variant="outline">Cancelled</Badge>
                        : past ? <Badge tone="warning">Awaiting scorecard</Badge> : <Badge tone="info">Scheduled</Badge>}
                  </div>
                  <div className="ap-row-score">
                    {i.scorecard ? <span className="ap-rec" data-rec={i.scorecard.recommendation}>{recommendationWord(i.scorecard.recommendation)}</span> : null}
                  </div>
                  <div className="ap-row-actions">
                    {i.scorecard && (
                      <Button variant="ghost" size="sm" trailingIcon={ChevronDown} aria-expanded={open} className="ap-expand-btn" onClick={() => setOpenCard(open ? null : i.id)}>
                        {open ? 'Hide scorecard' : 'Scorecard'}
                      </Button>
                    )}
                    {(can('interviews.schedule') || i.interviewerUserId === me.id) && (
                      <Link {...buttonProps('secondary', 'sm')} to={`/interviews/${i.id}`}>Open</Link>
                    )}
                    {i.status === 'SCHEDULED' && can('interviews.schedule') && (
                      <Menu
                        label={`Actions for the interview on ${fullDateTime(i.scheduledAt)}`}
                        trigger={props => <IconButton {...props} label={`More actions for the interview on ${fullDateTime(i.scheduledAt)}`} icon={Ellipsis} size="sm" />}
                        items={[
                          { label: 'Reschedule', icon: CalendarClock, onSelect: () => setRescheduling(i) },
                          { label: 'Cancel interview', danger: true, separated: true, onSelect: () => { cancel.reset(); setCancelling(i) } },
                        ]}
                      />
                    )}
                  </div>
                </div>
                {i.scorecard && (
                  <div className="ap-row-more" aria-hidden={!open || undefined} {...(open ? {} : ({ inert: '' } as Record<string, string>))}>
                    <div><div className="ap-row-more-inner"><ScorecardView scorecard={i.scorecard} questions={kit.data?.questions ?? []} interviewId={i.id} canSeeRevisions={can('interviews.schedule')} /></div></div>
                  </div>
                )}
              </li>
            )
          })}
        </ul>
      )}

      <ScheduleDialog
        open={rescheduling != null}
        jobId={jobId}
        detail={detail}
        interview={rescheduling}
        onClose={() => setRescheduling(null)}
        onScheduled={() => setRescheduling(null)}
      />
      <ActionDialog
        open={cancelling != null}
        title="Cancel this interview?"
        description={cancelling ? `${slotLabel(cancelling.scheduledAt, cancelling.durationMinutes)} with ${cancelling.interviewerName}. Both are notified.` : ''}
        tone="danger"
        confirmLabel="Cancel interview"
        cancelLabel="Keep interview"
        busy={cancel.isPending}
        error={cancel.error}
        onCancel={() => setCancelling(null)}
        onConfirm={() => { if (cancelling) cancel.mutate(cancelling) }}
      />
    </div>
  )
}

/**
 * Schedule (POST) or reschedule (PATCH, changed fields only; an emptied location is sent as "") one interview.
 * Times are local and named ("India Standard Time (UTC+5:30)"); duration has quick picks plus a custom value.
 */
export function ScheduleDialog({ open, jobId, detail, interview, onClose, onScheduled }: {
  open: boolean
  jobId: number
  detail: ApplicantDetail
  interview?: Interview | null
  onClose: () => void
  onScheduled: () => void
}) {
  const editing = interview != null
  return (
    <Dialog
      open={open}
      onClose={onClose}
      title={editing ? 'Reschedule the interview' : 'Schedule an interview'}
      description={editing ? `With ${interview!.interviewerName}. To change the interviewer, cancel and schedule a new one.` : `Times are in ${timeZoneLabel()}. The interviewer and the candidate are notified.`}
    >
      {open && <ScheduleForm key={interview?.id ?? 'new'} jobId={jobId} detail={detail} interview={interview ?? null} onClose={onClose} onDone={onScheduled} />}
    </Dialog>
  )
}

function ScheduleForm({ jobId, detail, interview, onClose, onDone }: { jobId: number; detail: ApplicantDetail; interview: Interview | null; onClose: () => void; onDone: () => void }) {
  const queryClient = useQueryClient()
  const { toast } = useToast()
  const editing = interview != null
  const interviewers = useQuery({ queryKey: qk.interviewers, queryFn: ({ signal }) => reviewApi.interviewers(signal), enabled: !editing, staleTime: 60_000 })
  const [who, setWho] = useState('')
  const [when, setWhen] = useState(() => (interview ? toLocalInput(interview.scheduledAt) : ''))
  const [duration, setDuration] = useState(() => String(interview?.durationMinutes ?? 45))
  const [location, setLocation] = useState(() => interview?.location ?? '')
  const [errors, setErrors] = useState<Record<string, string>>({})
  const custom = !(QUICK as readonly string[]).includes(duration)

  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: qk.applicant(jobId, detail.applicationId) })
    void queryClient.invalidateQueries({ queryKey: qk.applicants(jobId) })
    void queryClient.invalidateQueries({ queryKey: qk.interviews })
  }
  const schedule = useMutation({
    mutationFn: () => interviewsApi.schedule(jobId, detail.applicationId, {
      interviewerUserId: Number(who), scheduledAt: fromLocalInput(when) as string, durationMinutes: Number(duration), location: location.trim() || undefined,
    }),
    onSuccess: () => {
      toast({ tone: 'success', title: 'Interview scheduled', body: detail.applicationStatus === 'SHORTLISTED' ? 'The application moved to Interviewing. Both people are notified.' : 'The interviewer and the candidate are notified.' })
      refresh()
      onDone()
    },
  })
  const update = useMutation({
    mutationFn: (body: UpdateInterviewRequest) => interviewsApi.update(interview!.id, body),
    onSuccess: () => { toast({ tone: 'success', title: 'Interview updated', body: 'Both people are notified of the new time or place.' }); refresh(); onDone() },
  })
  const busy = schedule.isPending || update.isPending

  function submit(event: FormEvent) {
    event.preventDefault()
    const next: Record<string, string> = {}
    if (!editing && !who) next.who = 'Choose an interviewer.'
    const iso = fromLocalInput(when)
    const changedTime = interview ? iso != null && new Date(iso).getTime() !== new Date(interview.scheduledAt).getTime() : true
    if (!iso) next.when = 'Choose a date and time.'
    else if (changedTime && new Date(iso).getTime() <= Date.now()) next.when = 'Choose a time in the future.'
    const minutes = Number(duration)
    if (!Number.isInteger(minutes) || minutes < MIN || minutes > MAX) next.duration = `Use ${MIN} to ${MAX} minutes.`
    setErrors(next)
    if (Object.keys(next).length) {
      document.querySelector<HTMLElement>(`[data-field="${Object.keys(next)[0]}"] :is(input, select)`)?.focus()
      return
    }
    if (!editing) return schedule.mutate()
    const body: UpdateInterviewRequest = {}
    if (changedTime && iso) body.scheduledAt = iso
    if (minutes !== interview!.durationMinutes) body.durationMinutes = minutes
    if (location.trim() !== (interview!.location ?? '')) body.location = location.trim()
    if (Object.keys(body).length === 0) {
      toast({ tone: 'info', title: 'Nothing changed' })
      onClose()
      return
    }
    update.mutate(body)
  }

  return (
    <form className="sx-action-form" onSubmit={submit} noValidate>
      {!editing && (
        <div data-field="who">
          <Field label="Interviewer" error={errors.who} hint={interviewers.isError ? 'Could not load interviewers. Close and try again.' : undefined}>
            <Select value={who} onChange={e => setWho(e.target.value)} disabled={interviewers.isPending} autoFocus>
              <option value="">{interviewers.isPending ? 'Loading…' : 'Choose a person'}</option>
              {(interviewers.data ?? []).map(p => <option key={p.userId} value={p.userId}>{p.name} · {roleLabel(p.role)} · {p.email}</option>)}
            </Select>
          </Field>
        </div>
      )}
      <div data-field="when">
        <Field label="Date and time" error={errors.when} hint={timeZoneLabel()}>
          <Input type="datetime-local" value={when} onChange={e => setWhen(e.target.value)} autoFocus={editing} />
        </Field>
      </div>
      <div data-field="duration" className="ap-duration">
        <SegmentedControl
          label="Duration"
          value={custom ? 'custom' : duration}
          onChange={v => setDuration(v === 'custom' ? (custom ? duration : '75') : v)}
          options={[...QUICK.map(q => ({ value: q, label: `${q} min` })), { value: 'custom', label: 'Other' }]}
          size="sm"
        />
        {custom && (
          <Field label="Minutes" error={errors.duration} hint={`${MIN}–${MAX}`}>
            <Input type="number" min={MIN} max={MAX} step={5} inputMode="numeric" value={duration} onChange={e => setDuration(e.target.value)} />
          </Field>
        )}
      </div>
      <Field label="Location or video link" optional hint={editing ? 'Leave empty to remove it.' : undefined}>
        <Input value={location} maxLength={500} onChange={e => setLocation(e.target.value)} placeholder="Room 3B or https://meet…" />
      </Field>
      <InlineError error={schedule.error ?? update.error} />
      <div className="ap-dialog-actions">
        <Button variant="secondary" onClick={onClose} disabled={busy}>Cancel</Button>
        <Button type="submit" variant="primary" loading={busy} icon={editing ? undefined : CalendarClock}>{editing ? 'Save changes' : 'Schedule'}</Button>
      </div>
    </form>
  )
}

