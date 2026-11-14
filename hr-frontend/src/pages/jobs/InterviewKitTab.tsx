import { FormEvent, useId, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { interviewsApi } from '../../api/assessments'
import { errorMessage, isApiError } from '../../api/client'
import { qk } from '../../api/queryKeys'
import { ANCHOR_LEVELS, InterviewKit, InterviewQuestion, JobStaff, Requirement } from '../../api/types'
import { useAuth } from '../../auth/AuthContext'
import { ConfirmDialog } from '../../components/Dialog'
import { CloseIcon, PlusIcon, SparkIcon } from '../../components/Icons'
import { useNotices } from '../../components/Notices'
import { AnchorList } from '../../components/RatingScale'
import { EmptyPanel, ErrorPanel, FormError, LoadingPanel } from '../../components/States'
import { formatDateTime, humanize } from '../../lib/format'

/** Loads the kit; a 404 means "no kit yet". */
export async function fetchKit(jobId: number, signal?: AbortSignal): Promise<InterviewKit | null> {
  try {
    return await interviewsApi.kit(jobId, signal)
  } catch (cause) {
    if (isApiError(cause, 404)) return null
    throw cause
  }
}

/** Structured interview kit: generate, then edit questions, follow-ups and the four rating anchors. */
export function InterviewKitTab({ job }: { job: JobStaff }) {
  const { can } = useAuth()
  const canManage = can('interviewKit.manage')
  const queryClient = useQueryClient()
  const notices = useNotices()
  const kit = useQuery({ queryKey: qk.interviewKit(job.id), queryFn: ({ signal }) => fetchKit(job.id, signal) })
  const [editing, setEditing] = useState(false)
  const [confirmRegenerate, setConfirmRegenerate] = useState(false)

  const generate = useMutation({
    mutationFn: () => interviewsApi.generateKit(job.id),
    onSuccess: generated => {
      queryClient.setQueryData(qk.interviewKit(job.id), generated)
      setConfirmRegenerate(false)
      notices.success('Interview kit generated. Review the questions before interviews start.')
    },
    onError: cause => notices.error(errorMessage(cause, 'The interview kit could not be generated.')),
  })

  if (kit.isPending) return <LoadingPanel label="Loading interview kit…" />
  if (kit.isError) return <ErrorPanel error={kit.error} onRetry={() => void kit.refetch()} />

  const data = kit.data

  return (
    <section className="jobs-card" aria-labelledby="kit-heading">
      <div className="jobs-card-head">
        <div>
          <h2 id="kit-heading">Interview kit</h2>
          <p>Every interviewer asks the same questions and rates answers against the same anchors. A rating of 3 or 4 on a question linked to a requirement marks it as interview-verified.</p>
        </div>
        {canManage && !editing && (
          <div className="head-actions">
            {data && <button type="button" className="outline-button" onClick={() => setEditing(true)}>Edit questions</button>}
            <button
              type="button"
              className={data ? 'outline-button' : 'primary-button workspace-primary'}
              disabled={generate.isPending || job.requirements.length === 0}
              title={job.requirements.length === 0 ? 'Add requirements first.' : undefined}
              onClick={() => (data ? setConfirmRegenerate(true) : generate.mutate())}
            >
              <SparkIcon /> {generate.isPending ? 'Generating…' : data ? 'Regenerate' : 'Generate kit'}
            </button>
          </div>
        )}
      </div>

      {!data ? (
        <EmptyPanel title="No interview kit yet" compact>
          {canManage ? 'Generate questions from the job’s requirements, then adjust them.' : 'An HR manager can generate the kit from the job’s requirements.'}
        </EmptyPanel>
      ) : editing ? (
        <KitEditor jobId={job.id} kit={data} requirements={job.requirements} onDone={() => setEditing(false)} />
      ) : (
        <div className="kit-view">
          <p className="item-meta kit-meta">Source: {humanize(data.generatedBy)} · updated {formatDateTime(data.updatedAt)} · {data.questions.length} questions</p>
          <ol className="question-list">
            {data.questions.map(question => <QuestionView key={question.id} question={question} requirements={job.requirements} />)}
          </ol>
        </div>
      )}

      <ConfirmDialog
        open={confirmRegenerate}
        title="Regenerate the interview kit?"
        message="The current questions, including your edits, are replaced. Submitted scorecards keep their ratings."
        confirmLabel="Regenerate"
        danger
        busy={generate.isPending}
        onCancel={() => setConfirmRegenerate(false)}
        onConfirm={() => generate.mutate()}
      />
    </section>
  )
}

export function requirementName(requirements: Requirement[], id: number | null): string | null {
  if (id == null) return null
  const requirement = requirements.find(item => item.id === id)
  return requirement ? requirement.description : `Requirement #${id}`
}

function QuestionView({ question, requirements }: { question: InterviewQuestion; requirements: Requirement[] }) {
  const linked = requirementName(requirements, question.requirementId)
  return (
    <li className="question-card">
      <p className="question-text">{question.question}</p>
      {linked && <span className="req-tag">Probes: {linked}</span>}
      {question.followUps.length > 0 && (
        <>
          <h4>Follow-ups</h4>
          <ul className="follow-ups">{question.followUps.map((followUp, index) => <li key={index}>{followUp}</li>)}</ul>
        </>
      )}
      <h4>Rating anchors</h4>
      <AnchorList anchors={question.anchors} />
    </li>
  )
}

function newQuestion(existing: InterviewQuestion[]): InterviewQuestion {
  const ids = new Set(existing.map(question => question.id))
  let n = existing.length + 1
  while (ids.has(`q${n}`)) n += 1
  return { id: `q${n}`, requirementId: null, question: '', followUps: [], anchors: { '1': '', '2': '', '3': '', '4': '' } }
}

function KitEditor({ jobId, kit, requirements, onDone }: { jobId: number; kit: InterviewKit; requirements: Requirement[]; onDone: () => void }) {
  const queryClient = useQueryClient()
  const notices = useNotices()
  const baseId = useId()
  const [questions, setQuestions] = useState<InterviewQuestion[]>(() => kit.questions.map(question => ({ ...question, followUps: [...question.followUps], anchors: { ...question.anchors } })))
  const [errors, setErrors] = useState<Record<string, string>>({})

  const save = useMutation({
    mutationFn: (body: InterviewQuestion[]) => interviewsApi.saveKit(jobId, body),
    onSuccess: saved => {
      queryClient.setQueryData(qk.interviewKit(jobId), saved)
      notices.success('Interview kit saved.')
      onDone()
    },
  })

  const update = (index: number, values: Partial<InterviewQuestion>) => setQuestions(current => current.map((item, i) => (i === index ? { ...item, ...values } : item)))
  const move = (index: number, delta: number) => setQuestions(current => {
    const target = index + delta
    if (target < 0 || target >= current.length) return current
    const next = [...current]
    const [item] = next.splice(index, 1)
    next.splice(target, 0, item)
    return next
  })

  function submit(event: FormEvent) {
    event.preventDefault()
    const next: Record<string, string> = {}
    if (questions.length === 0) next.kit = 'Keep at least one question.'
    questions.forEach(question => {
      if (!question.question.trim()) next[question.id] = 'Write the question.'
      else if (ANCHOR_LEVELS.some(level => !question.anchors[level].trim())) next[question.id] = 'Describe all four rating levels.'
    })
    setErrors(next)
    if (Object.keys(next).length) {
      window.setTimeout(() => document.querySelector<HTMLElement>('.kit-editor [aria-invalid="true"]')?.focus(), 0)
      return
    }
    save.mutate(questions.map(question => ({
      ...question,
      question: question.question.trim(),
      followUps: question.followUps.map(item => item.trim()).filter(Boolean),
      anchors: { '1': question.anchors['1'].trim(), '2': question.anchors['2'].trim(), '3': question.anchors['3'].trim(), '4': question.anchors['4'].trim() },
    })))
  }

  return (
    <form className="kit-editor inline-editor" onSubmit={submit} noValidate>
      {errors.kit && <small className="field-error">{errors.kit}</small>}
      <ol className="question-list">
        {questions.map((question, index) => {
          const id = `${baseId}-${question.id}`
          const error = errors[question.id]
          return (
            <li key={question.id} className="question-card editing">
              <div className="rubric-criterion-head">
                <label className="req-field grow" htmlFor={`${id}-q`}>
                  <span>Question {index + 1}</span>
                  <textarea id={`${id}-q`} rows={2} value={question.question} onChange={event => update(index, { question: event.target.value })} aria-invalid={Boolean(error)} aria-describedby={error ? `${id}-error` : undefined} />
                </label>
                <div className="req-row-tools">
                  <button type="button" className="icon-button" onClick={() => move(index, -1)} disabled={index === 0} aria-label={`Move question ${index + 1} up`}>↑</button>
                  <button type="button" className="icon-button" onClick={() => move(index, 1)} disabled={index === questions.length - 1} aria-label={`Move question ${index + 1} down`}>↓</button>
                  <button type="button" className="icon-button danger" onClick={() => setQuestions(current => current.filter((_, i) => i !== index))} aria-label={`Remove question ${index + 1}`}><CloseIcon /></button>
                </div>
              </div>
              {error && <small className="field-error" id={`${id}-error`}>{error}</small>}
              <label className="req-field" htmlFor={`${id}-req`}>
                <span>Probes requirement</span>
                <select id={`${id}-req`} value={question.requirementId ?? ''} onChange={event => update(index, { requirementId: event.target.value === '' ? null : Number(event.target.value) })}>
                  <option value="">None (general question)</option>
                  {requirements.map(requirement => <option key={requirement.id} value={requirement.id}>{humanize(requirement.type)}: {requirement.description}</option>)}
                </select>
              </label>
              <fieldset className="follow-up-editor">
                <legend>Follow-up questions</legend>
                {question.followUps.map((followUp, followIndex) => (
                  <div key={followIndex} className="follow-up-row">
                    <label className="sr-only" htmlFor={`${id}-f${followIndex}`}>Follow-up {followIndex + 1}</label>
                    <input id={`${id}-f${followIndex}`} value={followUp} onChange={event => update(index, { followUps: question.followUps.map((item, i) => (i === followIndex ? event.target.value : item)) })} />
                    <button type="button" className="icon-button danger" onClick={() => update(index, { followUps: question.followUps.filter((_, i) => i !== followIndex) })} aria-label={`Remove follow-up ${followIndex + 1}`}><CloseIcon /></button>
                  </div>
                ))}
                <button type="button" className="outline-button" onClick={() => update(index, { followUps: [...question.followUps, ''] })}><PlusIcon /> Add follow-up</button>
              </fieldset>
              <div className="anchor-grid">
                {ANCHOR_LEVELS.map(level => (
                  <label key={level} className="req-field" htmlFor={`${id}-a${level}`}>
                    <span>Rating {level}</span>
                    <textarea id={`${id}-a${level}`} rows={3} value={question.anchors[level]} onChange={event => update(index, { anchors: { ...question.anchors, [level]: event.target.value } })} />
                  </label>
                ))}
              </div>
            </li>
          )
        })}
      </ol>
      <button type="button" className="outline-button" onClick={() => setQuestions(current => [...current, newQuestion(current)])}><PlusIcon /> Add question</button>
      <FormError error={save.error} />
      <div className="editor-actions">
        <button type="button" className="outline-button" onClick={onDone} disabled={save.isPending}>Cancel</button>
        <button type="submit" className="primary-button workspace-primary" disabled={save.isPending}>{save.isPending ? 'Saving…' : 'Save kit'}</button>
      </div>
    </form>
  )
}
