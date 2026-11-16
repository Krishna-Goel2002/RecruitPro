import { FormEvent, useMemo, useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { interviewsApi } from '../../api/assessments'
import { errorMessage, isApiError } from '../../api/client'
import { qk } from '../../api/queryKeys'
import { ANCHOR_LEVELS, InterviewKit, InterviewQuestion, JobStaff, Requirement } from '../../api/types'
import { useAuth } from '../../auth/AuthContext'
import { ActionDialog } from '../../components/ActionDialog'
import { QueryError } from '../../components/QueryError'
import {
  Alert, Button, EmptyState, Field, IconButton, Input, LoadingRegion, ReqMarker, Section, Select, Skeleton, Textarea, Tooltip, useToast,
} from '../../design/components'
import { plural, relativeTime } from '../../design/lib/format'
import { ArrowDown, ArrowUp, CalendarClock, Plus, RefreshCw, Trash2, X } from '../../design/icons'
import { humanize } from '../../lib/format'
import { useDiscardGuard, useLeaveGuard } from '../../lib/leaveGuard'
import { AnchorGrid } from './WorkSamplesTab'

/** Loads the kit; a 404 means "no kit yet". */
export async function fetchKit(jobId: number, signal?: AbortSignal): Promise<InterviewKit | null> {
  try {
    return await interviewsApi.kit(jobId, signal)
  } catch (cause) {
    if (isApiError(cause, 404)) return null
    throw cause
  }
}

export function requirementName(requirements: Requirement[], id: number | null): string | null {
  if (id == null) return null
  const requirement = requirements.find(item => item.id === id)
  return requirement ? requirement.description : `Requirement #${id}`
}

/** Structured interview kit: generate, read (anchors in a 1–4 grid, coverage of the must-haves), edit with a sticky bar. */
export function InterviewKitTab({ job }: { job: JobStaff }) {
  const { can } = useAuth()
  // The server refuses kits for cancelled jobs (InterviewKitService: "Cancelled jobs cannot get an interview kit").
  const canManage = can('interviewKit.manage') && job.status !== 'CANCELLED'
  const queryClient = useQueryClient()
  const { toast } = useToast()
  const kit = useQuery({ queryKey: qk.interviewKit(job.id), queryFn: ({ signal }) => fetchKit(job.id, signal) })
  const [editing, setEditing] = useState(false)
  const [confirmRegenerate, setConfirmRegenerate] = useState(false)

  const generate = useMutation({
    mutationFn: () => interviewsApi.generateKit(job.id),
    onSuccess: generated => {
      queryClient.setQueryData(qk.interviewKit(job.id), generated)
      // Interview pages embed the kit: refetch them so interviewers see the new questions.
      void queryClient.invalidateQueries({ queryKey: qk.interviews })
      setConfirmRegenerate(false)
      toast({ tone: 'success', title: `Interview kit ready: ${plural(generated.questions.length, 'question')}`, body: 'Review the questions before interviews start.' })
    },
    onError: cause => toast({ tone: 'danger', title: 'The interview kit could not be generated', body: errorMessage(cause) }),
  })

  if (kit.isPending) {
    return <LoadingRegion label="Loading the interview kit"><div className="hra-list-skel">{[0, 1, 2].map(i => <Skeleton key={i} width="100%" height={72} />)}</div></LoadingRegion>
  }
  if (kit.isError) return <QueryError error={kit.error} title="The interview kit could not be loaded" onRetry={() => void kit.refetch()} />

  const data = kit.data
  const noRequirements = job.requirements.length === 0
  // A disabled button cannot show a tooltip (no hover or focus), so the reason is visible text instead.
  const generateButton = canManage && (noRequirements ? (
    <span className="hra-disabled-reason">
      <Button variant="primary" icon={Plus} disabled>Generate kit</Button>
      <span className="subtle">Add requirements to the job first.</span>
    </span>
  ) : (
    <Tooltip content="One or two questions per requirement, each with four rating anchors." labelOnly>
      <Button variant="primary" icon={Plus} loading={generate.isPending} onClick={() => generate.mutate()}>Generate kit</Button>
    </Tooltip>
  ))

  if (!data) {
    return (
      <Section title="Interview kit" description="Every interviewer asks the same questions and rates answers against the same anchors.">
        <EmptyState
          icon={CalendarClock}
          title="No interview kit yet"
          description={canManage ? 'Generate questions from the requirements, then adjust them. A rating of 3 or 4 on a question linked to a requirement marks it as interview-verified.' : 'An HR manager can generate the kit from the job’s requirements.'}
          action={generateButton || undefined}
        />
      </Section>
    )
  }

  const probed = new Set(data.questions.map(q => q.requirementId).filter((id): id is number => id != null))
  const unprobedMust = job.requirements.filter(r => r.mandatory && !probed.has(r.id))
  const unprobedNice = job.requirements.filter(r => !r.mandatory && !probed.has(r.id))

  if (editing) return <KitEditor jobId={job.id} kit={data} requirements={job.requirements} onDone={() => setEditing(false)} />

  return (
    <Section
      title="Interview kit"
      description={`${plural(data.questions.length, 'question')} · ${data.generatedBy === 'manual' ? 'edited by your team' : `drafted by the ${data.generatedBy === 'llm' ? 'assistant' : 'rules engine'}`} · updated ${relativeTime(data.updatedAt)}`}
      actions={canManage ? (
        <span className="cluster">
          {noRequirements
            ? <span className="hra-disabled-reason"><span className="subtle">Add requirements to regenerate.</span><Button variant="ghost" icon={RefreshCw} disabled>Regenerate</Button></span>
            : <Button variant="ghost" icon={RefreshCw} onClick={() => setConfirmRegenerate(true)}>Regenerate</Button>}
          <Button onClick={() => setEditing(true)}>Edit questions</Button>
        </span>
      ) : undefined}
    >
      {unprobedMust.length === 0 ? (
        <p className="hra-kit-coverage" data-ok="">Every must-have has at least one question.{unprobedNice.length ? ` No question yet for: ${unprobedNice.map(r => r.description).join(', ')}.` : ''}</p>
      ) : (
        <Alert tone="warning" className="hra-kit-alert">No question probes these must-haves yet: {unprobedMust.map(r => r.description).join(', ')}.</Alert>
      )}
      <ol className="hra-questions">
        {data.questions.map((question, index) => {
          const linked = job.requirements.find(r => r.id === question.requirementId)
          return (
            <li key={question.id} className="hra-question">
              <span className="hra-q-num numeric" aria-hidden="true">{index + 1}</span>
              <div className="hra-q-body">
                <p className="hra-q-text">{question.question}</p>
                <p className="hra-q-probes">
                  {linked ? <>Probes <strong>{linked.description}</strong> <ReqMarker kind={linked.mandatory && linked.knockout ? 'gate' : linked.mandatory ? 'must' : 'nice'} /></> : <span className="subtle">General question</span>}
                </p>
                {question.followUps.length > 0 && (
                  <ul className="hra-followups" aria-label="Follow-up questions">
                    {question.followUps.map((followUp, i) => <li key={i}>{followUp}</li>)}
                  </ul>
                )}
                <AnchorGrid anchors={question.anchors} />
              </div>
            </li>
          )
        })}
      </ol>

      <ActionDialog
        open={confirmRegenerate}
        size="sm"
        title="Regenerate the interview kit?"
        confirmLabel="Regenerate"
        tone="danger"
        busy={generate.isPending}
        onCancel={() => setConfirmRegenerate(false)}
        onConfirm={() => generate.mutate()}
      >
        <p>The current questions, including your edits, are replaced. Submitted scorecards keep their ratings.</p>
      </ActionDialog>
    </Section>
  )
}

/** Server limits (InterviewKitService). */
const MAX_QUESTIONS = 60
const MAX_QUESTION = 2000
const MAX_FOLLOW_UPS = 10
const MAX_FOLLOW_UP = 1000
const MAX_KIT_ANCHOR = 1000

/**
 * A new question id that was never used in this editing session: a removed question can come back with Undo, so ids of
 * removed questions stay reserved (otherwise Undo would create a duplicate id that the server refuses).
 */
function newQuestion(existing: InterviewQuestion[], reserved: Set<string>): InterviewQuestion {
  const ids = new Set([...existing.map(question => question.id), ...reserved])
  let n = existing.length + 1
  while (ids.has(`q${n}`)) n += 1
  reserved.add(`q${n}`)
  return { id: `q${n}`, requirementId: null, question: '', followUps: [], anchors: { '1': '', '2': '', '3': '', '4': '' } }
}

function KitEditor({ jobId, kit, requirements, onDone }: { jobId: number; kit: InterviewKit; requirements: Requirement[]; onDone: () => void }) {
  const queryClient = useQueryClient()
  const { toast } = useToast()
  const initial = useMemo(() => kit.questions.map(question => ({ ...question, followUps: [...question.followUps], anchors: { ...question.anchors } })), [kit])
  const [questions, setQuestions] = useState<InterviewQuestion[]>(initial)
  const [errors, setErrors] = useState<Record<string, string>>({})
  const reserved = useRef(new Set(kit.questions.map(question => question.id)))
  const dirty = JSON.stringify(questions) !== JSON.stringify(initial)

  const save = useMutation({
    mutationFn: (body: InterviewQuestion[]) => interviewsApi.saveKit(jobId, body),
    onSuccess: saved => {
      queryClient.setQueryData(qk.interviewKit(jobId), saved)
      void queryClient.invalidateQueries({ queryKey: qk.interviews })
      toast({ tone: 'success', title: 'Interview kit saved' })
      onDone()
    },
  })

  // Cancel with edits asks first; so do links and the other job tabs (they would unmount the editor).
  const discard = useDiscardGuard(dirty, onDone, 'changes to the interview kit')
  const leaveGuard = useLeaveGuard(dirty && !save.isPending && !save.isSuccess, { body: <p>Your changes to the interview kit have not been saved yet and will be lost.</p> })

  const update = (index: number, values: Partial<InterviewQuestion>) => setQuestions(current => current.map((item, i) => (i === index ? { ...item, ...values } : item)))
  const move = (index: number, delta: number) => setQuestions(current => {
    const target = index + delta
    if (target < 0 || target >= current.length) return current
    const next = [...current]
    const [item] = next.splice(index, 1)
    next.splice(target, 0, item)
    return next
  })
  const remove = (index: number) => {
    const removed = questions[index]
    setQuestions(current => current.filter((_, i) => i !== index))
    toast({ tone: 'info', title: `Question ${index + 1} removed`, duration: 6000, action: { label: 'Undo', onClick: () => setQuestions(current => (current.some(q => q.id === removed.id) ? current : [...current.slice(0, index), removed, ...current.slice(index)])) } })
  }

  function submit(event: FormEvent) {
    event.preventDefault()
    const next: Record<string, string> = {}
    if (questions.length === 0) next.kit = 'Keep at least one question.'
    questions.forEach(question => {
      if (!question.question.trim()) next[question.id] = 'Write the question.'
      else if (question.question.trim().length > MAX_QUESTION) next[question.id] = `Use ${MAX_QUESTION} characters or fewer.`
      ANCHOR_LEVELS.forEach(level => {
        const text = (question.anchors[level] ?? '').trim()
        if (!text) next[`${question.id}-${level}`] = `Describe rating ${level}.`
        else if (text.length > MAX_KIT_ANCHOR) next[`${question.id}-${level}`] = `Use ${MAX_KIT_ANCHOR} characters or fewer.`
      })
    })
    if (questions.length > MAX_QUESTIONS) next.kit = `Keep at most ${MAX_QUESTIONS} questions.`
    setErrors(next)
    if (Object.keys(next).length) {
      window.setTimeout(() => document.querySelector<HTMLElement>('.hra-kit-editor [aria-invalid="true"]')?.focus(), 0)
      return
    }
    save.mutate(questions.map(question => ({
      ...question,
      question: question.question.trim(),
      followUps: question.followUps.map(item => item.trim()).filter(Boolean).slice(0, MAX_FOLLOW_UPS),
      anchors: { '1': (question.anchors['1'] ?? '').trim(), '2': (question.anchors['2'] ?? '').trim(), '3': (question.anchors['3'] ?? '').trim(), '4': (question.anchors['4'] ?? '').trim() },
    })))
  }

  return (
    <>
    <form className="hra-kit-editor" onSubmit={submit} noValidate aria-label="Edit interview questions">
      <header className="hra-kit-editor-head">
        <h2 className="t-h2">Edit interview questions</h2>
        <p className="subtle">All four rating anchors are required, so every interviewer scores against the same description.</p>
      </header>
      {errors.kit && <p className="ui-field-error" role="alert">{errors.kit}</p>}
      <ol className="hra-questions" data-editing="">
        {questions.map((question, index) => (
          <li key={question.id} className="hra-question">
            <span className="hra-q-num numeric" aria-hidden="true">{index + 1}</span>
            <div className="hra-q-body stack-6">
              <div className="hra-criterion-head">
                <Field label={`Question ${index + 1}`} error={errors[question.id]} className="grow">
                  <Textarea rows={2} maxRows={6} maxLength={MAX_QUESTION} value={question.question} onChange={event => update(index, { question: event.target.value })} />
                </Field>
                <span className="hra-q-tools">
                  <IconButton label={`Move question ${index + 1} up`} icon={ArrowUp} size="sm" disabled={index === 0} onClick={() => move(index, -1)} />
                  <IconButton label={`Move question ${index + 1} down`} icon={ArrowDown} size="sm" disabled={index === questions.length - 1} onClick={() => move(index, 1)} />
                  <IconButton label={`Remove question ${index + 1}`} icon={Trash2} size="sm" onClick={() => remove(index)} />
                </span>
              </div>
              <Field label="Probes requirement" optional>
                <Select value={question.requirementId ?? ''} onChange={event => update(index, { requirementId: event.target.value === '' ? null : Number(event.target.value) })}>
                  <option value="">None (general question)</option>
                  {requirements.map(requirement => <option key={requirement.id} value={requirement.id}>{humanize(requirement.type)}: {requirement.description}</option>)}
                </Select>
              </Field>
              <fieldset className="hra-followup-edit">
                <legend className="ui-field-label">Follow-up questions</legend>
                {question.followUps.map((followUp, followIndex) => (
                  <div key={followIndex} className="hra-followup-row">
                    <Input aria-label={`Follow-up ${followIndex + 1} for question ${index + 1}`} maxLength={MAX_FOLLOW_UP} value={followUp} onChange={event => update(index, { followUps: question.followUps.map((item, i) => (i === followIndex ? event.target.value : item)) })} />
                    <IconButton label={`Remove follow-up ${followIndex + 1} of question ${index + 1}`} icon={X} size="sm" onClick={() => update(index, { followUps: question.followUps.filter((_, i) => i !== followIndex) })} />
                  </div>
                ))}
                {question.followUps.length < MAX_FOLLOW_UPS && <Button size="sm" variant="ghost" icon={Plus} onClick={() => update(index, { followUps: [...question.followUps, ''] })}>Add follow-up</Button>}
              </fieldset>
              <div className="hra-anchor-edit" data-cols="4">
                {ANCHOR_LEVELS.map(level => (
                  <Field key={level} label={`Rating ${level}`} error={errors[`${question.id}-${level}`]}>
                    <Textarea rows={2} maxRows={6} maxLength={MAX_KIT_ANCHOR} value={question.anchors[level] ?? ''} onChange={event => update(index, { anchors: { ...question.anchors, [level]: event.target.value } })} />
                  </Field>
                ))}
              </div>
            </div>
          </li>
        ))}
      </ol>
      {questions.length < MAX_QUESTIONS
        ? <Button icon={Plus} onClick={() => setQuestions(current => [...current, newQuestion(current, reserved.current)])}>Add question</Button>
        : <p className="subtle">{MAX_QUESTIONS} questions is the maximum.</p>}
      <div className="hra-sticky-bar" role="region" aria-label="Save the interview kit">
        <p className="subtle">{save.isError ? <span className="ui-field-error">{errorMessage(save.error)}</span> : `${plural(questions.length, 'question')}`}</p>
        <span className="cluster">
          <Button variant="ghost" onClick={discard.request} disabled={save.isPending}>Cancel</Button>
          <Button type="submit" variant="primary" loading={save.isPending}>Save kit</Button>
        </span>
      </div>
    </form>
    {discard.dialog}
    {leaveGuard}
    </>
  )
}
