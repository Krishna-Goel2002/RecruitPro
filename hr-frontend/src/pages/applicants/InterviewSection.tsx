import { FormEvent, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { interviewsApi } from '../../api/assessments'
import { qk } from '../../api/queryKeys'
import { reviewApi } from '../../api/review'
import type { ApplicantDetail, Interview, InterviewQuestion, UpdateInterviewRequest } from '../../api/types'
import { useAuth, useCurrentUser } from '../../auth/AuthContext'
import { StatusBadge } from '../../components/Badges'
import { ConfirmDialog, Dialog } from '../../components/Dialog'
import { Field } from '../../components/Field'
import { CalendarIcon } from '../../components/Icons'
import { useNotices } from '../../components/Notices'
import { FormError } from '../../components/States'
import { formatDateTime, fromLocalInput, humanize, roleLabel, toLocalInput } from '../../lib/format'
import { fetchKit } from '../jobs/InterviewKitTab'
import { ScorecardView } from '../interviews/ScorecardView'

const SCHEDULABLE = ['SHORTLISTED', 'INTERVIEWING']
/** Backend limits (ScheduleInterviewRequest / UpdateInterviewRequest): 5–480 minutes. */
const MIN_DURATION = 5
const MAX_DURATION = 480

/**
 * Schedule interviews (interviewer, time, duration, location), reschedule or cancel them, and read submitted
 * scorecards. Rescheduling uses `PATCH /interviews/{id}`; a changed time or place re-notifies the candidate and the
 * interviewer, a cancellation sends INTERVIEW_CANCELLED to both (WP-BE-C).
 */
export function InterviewSection({ jobId, detail }: { jobId: number; detail: ApplicantDetail }) {
  const { can } = useAuth()
  const me = useCurrentUser()
  const queryClient = useQueryClient()
  const notices = useNotices()
  const [scheduleOpen, setScheduleOpen] = useState(false)
  const [cancelling, setCancelling] = useState<Interview | null>(null)
  const [rescheduling, setRescheduling] = useState<Interview | null>(null)
  const [openCard, setOpenCard] = useState<number | null>(null)
  const kit = useQuery({ queryKey: qk.interviewKit(jobId), queryFn: ({ signal }) => fetchKit(jobId, signal), enabled: detail.interviews.some(interview => interview.scorecard) })
  const canSchedule = can('interviews.schedule') && SCHEDULABLE.includes(detail.applicationStatus)

  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: qk.applicant(jobId, detail.applicationId) })
    void queryClient.invalidateQueries({ queryKey: qk.applicants(jobId) })
    void queryClient.invalidateQueries({ queryKey: qk.interviews })
  }

  const cancel = useMutation({
    mutationFn: (interview: Interview) => interviewsApi.update(interview.id, { status: 'CANCELLED' }),
    onSuccess: () => { setCancelling(null); notices.success('Interview cancelled.'); refresh() },
  })

  return (
    <section className="detail-card section-card" aria-labelledby="interviews-heading">
      <div className="section-head">
        <div>
          <h2 id="interviews-heading">Interviews</h2>
          <p>Structured interviews use the job’s interview kit. A rating of 3 or 4 on a requirement-linked question marks it as interview-verified.</p>
        </div>
        {canSchedule && <button type="button" className="outline-button" onClick={() => setScheduleOpen(true)}><CalendarIcon /> Schedule interview</button>}
      </div>
      {can('interviews.schedule') && !SCHEDULABLE.includes(detail.applicationStatus) && (
        <p className="muted-copy">Interviews can be scheduled once the application is shortlisted. Scheduling the first one moves it to Interviewing.</p>
      )}

      {detail.interviews.length === 0 ? <p className="muted-copy">No interviews scheduled.</p> : (
        <ul className="card-list">
          {detail.interviews.map(interview => (
            <li key={interview.id} className="item-card">
              <div className="item-card-head">
                <div>
                  <h3>{formatDateTime(interview.scheduledAt)} · {interview.durationMinutes} min</h3>
                  <p className="item-meta">With {interview.interviewerName}{interview.location ? ` · ${interview.location}` : ''}</p>
                </div>
                <StatusBadge status={interview.status} />
              </div>
              <div className="item-actions">
                {(can('interviews.schedule') || interview.interviewerUserId === me.id) && (
                  <Link className="outline-button" to={`/interviews/${interview.id}`}>Open interview</Link>
                )}
                {interview.scorecard && (
                  <button type="button" className="outline-button" aria-expanded={openCard === interview.id} onClick={() => setOpenCard(openCard === interview.id ? null : interview.id)}>
                    {openCard === interview.id ? 'Hide scorecard' : `Scorecard: ${humanize(interview.scorecard.recommendation)}`}
                  </button>
                )}
                {interview.status === 'SCHEDULED' && can('interviews.schedule') && (
                  <button type="button" className="outline-button" onClick={() => setRescheduling(interview)}>Reschedule</button>
                )}
                {interview.status === 'SCHEDULED' && can('interviews.schedule') && (
                  <button type="button" className="outline-button danger-outline" onClick={() => { cancel.reset(); setCancelling(interview) }}>Cancel interview</button>
                )}
              </div>
              {openCard === interview.id && interview.scorecard && (
                <ScorecardView scorecard={interview.scorecard} questions={kit.data?.questions ?? ([] as InterviewQuestion[])} />
              )}
            </li>
          ))}
        </ul>
      )}

      <ScheduleDialog
        open={scheduleOpen}
        jobId={jobId}
        applicationId={detail.applicationId}
        onClose={() => setScheduleOpen(false)}
        onScheduled={() => { setScheduleOpen(false); notices.success('Interview scheduled. The interviewer and the candidate are notified.'); refresh() }}
      />
      <RescheduleDialog
        interview={rescheduling}
        onClose={() => setRescheduling(null)}
        onSaved={changed => {
          setRescheduling(null)
          notices.success(changed ? 'Interview updated. The interviewer and the candidate are notified of the new time or place.' : 'Nothing changed.')
          if (changed) refresh()
        }}
      />
      <ConfirmDialog
        open={cancelling != null}
        title="Cancel this interview?"
        message={cancelling ? `${formatDateTime(cancelling.scheduledAt)} with ${cancelling.interviewerName}.` : ''}
        confirmLabel="Cancel interview"
        danger
        busy={cancel.isPending}
        error={cancel.error}
        onCancel={() => setCancelling(null)}
        onConfirm={() => { if (cancelling) cancel.mutate(cancelling) }}
      />
    </section>
  )
}

function ScheduleDialog({ open, jobId, applicationId, onClose, onScheduled }: {
  open: boolean
  jobId: number
  applicationId: number
  onClose: () => void
  onScheduled: () => void
}) {
  const interviewers = useQuery({ queryKey: qk.interviewers, queryFn: ({ signal }) => reviewApi.interviewers(signal), enabled: open, staleTime: 60_000 })
  const [interviewer, setInterviewer] = useState('')
  const [when, setWhen] = useState('')
  const [duration, setDuration] = useState('45')
  const [location, setLocation] = useState('')
  const [errors, setErrors] = useState<Record<string, string>>({})

  const schedule = useMutation({
    mutationFn: () => interviewsApi.schedule(jobId, applicationId, {
      interviewerUserId: Number(interviewer),
      scheduledAt: fromLocalInput(when) as string,
      durationMinutes: Number(duration),
      location: location.trim() || undefined,
    }),
    onSuccess: () => { setInterviewer(''); setWhen(''); setDuration('45'); setLocation(''); onScheduled() },
  })

  function submit(event: FormEvent) {
    event.preventDefault()
    const next: Record<string, string> = {}
    if (!interviewer) next.interviewer = 'Choose an interviewer.'
    const iso = fromLocalInput(when)
    if (!iso) next.when = 'Choose a date and time.'
    else if (new Date(iso).getTime() <= Date.now()) next.when = 'Choose a time in the future.'
    const minutes = Number(duration)
    if (!Number.isInteger(minutes) || minutes < MIN_DURATION || minutes > MAX_DURATION) next.duration = `Use ${MIN_DURATION} to ${MAX_DURATION} minutes.`
    setErrors(next)
    if (Object.keys(next).length === 0) schedule.mutate()
  }

  return (
    <Dialog open={open} title="Schedule an interview" description="Times are entered in your local time zone and stored in UTC." onClose={onClose} busy={schedule.isPending}>
      <form className="dialog-form" onSubmit={submit} noValidate>
        <Field label="Interviewer" required error={errors.interviewer} hint={interviewers.isError ? 'Could not load interviewers.' : undefined}>
          <select value={interviewer} onChange={event => setInterviewer(event.target.value)} disabled={interviewers.isPending} data-autofocus>
            <option value="">{interviewers.isPending ? 'Loading…' : 'Choose a person'}</option>
            {(interviewers.data ?? []).map(person => <option key={person.userId} value={person.userId}>{person.name} — {roleLabel(person.role)} ({person.email})</option>)}
          </select>
        </Field>
        <Field label="Date and time" required error={errors.when}>
          <input type="datetime-local" value={when} onChange={event => setWhen(event.target.value)} />
        </Field>
        <div className="form-grid compact">
          <Field label="Duration (minutes)" required error={errors.duration}>
            <input type="number" min={MIN_DURATION} max={MAX_DURATION} step={5} inputMode="numeric" value={duration} onChange={event => setDuration(event.target.value)} />
          </Field>
          <Field label="Location or video link" hint="Optional">
            <input value={location} maxLength={500} onChange={event => setLocation(event.target.value)} placeholder="Room 3B or https://meet…" />
          </Field>
        </div>
        <FormError error={schedule.error} />
        <div className="dialog-actions">
          <button type="button" className="outline-button" onClick={onClose} disabled={schedule.isPending}>Cancel</button>
          <button type="submit" className="primary-button workspace-primary" disabled={schedule.isPending}>{schedule.isPending ? 'Scheduling…' : 'Schedule'}</button>
        </div>
      </form>
    </Dialog>
  )
}

/**
 * Reschedule form for a SCHEDULED interview. Only changed fields are sent (absent = unchanged on the server);
 * an emptied location is sent as "" so the server clears it.
 */
function RescheduleDialog({ interview, onClose, onSaved }: {
  interview: Interview | null
  onClose: () => void
  onSaved: (changed: boolean) => void
}) {
  return (
    <Dialog open={interview != null} title="Reschedule the interview" description="Times are entered in your local time zone and stored in UTC." onClose={onClose}>
      {interview && <RescheduleForm key={interview.id} interview={interview} onClose={onClose} onSaved={onSaved} />}
    </Dialog>
  )
}

function RescheduleForm({ interview, onClose, onSaved }: { interview: Interview; onClose: () => void; onSaved: (changed: boolean) => void }) {
  const [when, setWhen] = useState(() => toLocalInput(interview.scheduledAt))
  const [duration, setDuration] = useState(() => String(interview.durationMinutes))
  const [location, setLocation] = useState(() => interview.location ?? '')
  const [errors, setErrors] = useState<Record<string, string>>({})

  const save = useMutation({
    mutationFn: (body: UpdateInterviewRequest) => interviewsApi.update(interview.id, body),
    onSuccess: () => onSaved(true),
  })

  function submit(event: FormEvent) {
    event.preventDefault()
    const next: Record<string, string> = {}
    const body: UpdateInterviewRequest = {}
    const iso = fromLocalInput(when)
    if (!iso) next.when = 'Choose a date and time.'
    else if (new Date(iso).getTime() !== new Date(interview.scheduledAt).getTime()) {
      if (new Date(iso).getTime() <= Date.now()) next.when = 'Choose a time in the future.'
      else body.scheduledAt = iso
    }
    const minutes = Number(duration)
    if (!Number.isInteger(minutes) || minutes < MIN_DURATION || minutes > MAX_DURATION) next.duration = `Use ${MIN_DURATION} to ${MAX_DURATION} minutes.`
    else if (minutes !== interview.durationMinutes) body.durationMinutes = minutes
    const place = location.trim()
    if (place !== (interview.location ?? '')) body.location = place
    setErrors(next)
    if (Object.keys(next).length > 0) return
    if (Object.keys(body).length === 0) { onSaved(false); return }
    save.mutate(body)
  }

  return (
    <form className="dialog-form" onSubmit={submit} noValidate>
      <p className="muted-copy">With {interview.interviewerName}. To change the interviewer, cancel this interview and schedule a new one.</p>
      <Field label="Date and time" required error={errors.when}>
        <input type="datetime-local" value={when} onChange={event => setWhen(event.target.value)} data-autofocus />
      </Field>
      <div className="form-grid compact">
        <Field label="Duration (minutes)" required error={errors.duration}>
          <input type="number" min={MIN_DURATION} max={MAX_DURATION} step={5} inputMode="numeric" value={duration} onChange={event => setDuration(event.target.value)} />
        </Field>
        <Field label="Location or video link" hint="Optional; leave empty to remove it">
          <input value={location} maxLength={500} onChange={event => setLocation(event.target.value)} placeholder="Room 3B or https://meet…" />
        </Field>
      </div>
      <FormError error={save.error} />
      <div className="dialog-actions">
        <button type="button" className="outline-button" onClick={onClose} disabled={save.isPending}>Cancel</button>
        <button type="submit" className="primary-button workspace-primary" disabled={save.isPending}>{save.isPending ? 'Saving…' : 'Save changes'}</button>
      </div>
    </form>
  )
}
