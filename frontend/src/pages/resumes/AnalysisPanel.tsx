import { useId, useLayoutEffect, useRef, useState, type FormEvent } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { errorMessage } from '../../api/client'
import { resumesApi } from '../../api/endpoints'
import { queryKeys } from '../../api/queryKeys'
import type { Resume, ResumeAnalysis, ResumeFact } from '../../api/types'
import { ErrorState, FormError } from '../../components/Feedback'
import { useNotify } from '../../components/Notices'
import { Alert, Button, EvidenceLadder, Field, LoadingRegion, ProvenanceLine, Quote, Skeleton, SkeletonText, Textarea } from '../../design/components'
import { Pencil, RefreshCw, ScrollText } from '../../design/icons'
import { formatDate } from '../../design/lib/format'
import { stagger } from '../../design/motion'
import { FACT_GROUPS, analysisErrorText, explainFlag, humanize } from '../../lib/labels'
import type { useResumeAnalysis } from './useResumeAnalysis'

type AnalysisHook = ReturnType<typeof useResumeAnalysis>

const FACT_MAX = 1000

/** The analysis of one resume: a status line, notes about the file, and "What we read" grouped by kind. */
export function AnalysisPanel({ resume, analysis }: { resume: Resume; analysis: AnalysisHook }) {
  const { query, stalled, checkAgain } = analysis
  const notify = useNotify()
  const retry = useMutation({
    mutationFn: () => resumesApi.retryAnalysis(resume.id),
    onSuccess: () => {
      notify('success', 'Analysis queued again', { body: 'This page updates when it finishes.' })
      checkAgain()
    },
  })

  if (query.isPending) {
    return (
      <LoadingRegion label="Loading the analysis">
        <Skeleton width="100%" height={44} style={{ borderRadius: 6 }} />
        <div style={{ marginTop: 24 }}>
          <SkeletonText lines={4} />
        </div>
      </LoadingRegion>
    )
  }
  if (query.isError) return <ErrorState error={query.error} title="The analysis could not be loaded" onRetry={() => void query.refetch()} />

  const data = query.data
  const done = data.status === 'COMPLETED' || data.status === 'PARTIAL'
  return (
    <div className="r-panel">
      <StatusLine analysis={data} stalled={stalled} checking={query.isFetching} onCheckAgain={checkAgain} onRetry={() => retry.mutate()} retrying={retry.isPending} />
      <FormError error={retry.error} />
      {data.flags && data.flags.length > 0 && (
        <details className="r-flags">
          <summary>
            Notes about this file <span className="numeric">({data.flags.length})</span>
          </summary>
          <ul>
            {data.flags.map((flag, index) => {
              const explanation = explainFlag(flag.code, flag.detail)
              return (
                <li key={`${flag.code}-${index}`}>
                  <p className="t-strong">{explanation.title}</p>
                  <p className="c-muted">{explanation.text}</p>
                </li>
              )
            })}
          </ul>
        </details>
      )}
      {(data.status === 'PENDING' || data.status === 'PROCESSING') && !stalled && (
        <div className="r-reading" aria-hidden="true">
          {[0, 1, 2].map(i => (
            <div key={i} className="c-skel-row">
              <Skeleton width={[160, 220, 120][i]} height={12} />
              <Skeleton width="80%" height={10} />
            </div>
          ))}
        </div>
      )}
      {done && <FactGroups resumeId={resume.id} facts={data.facts} />}
    </div>
  )
}

function StatusLine({
  analysis, stalled, checking, onCheckAgain, onRetry, retrying,
}: { analysis: ResumeAnalysis; stalled: boolean; checking: boolean; onCheckAgain: () => void; onRetry: () => void; retrying: boolean }) {
  switch (analysis.status) {
    case 'PENDING':
    case 'PROCESSING':
      return stalled ? (
        <Alert
          tone="info"
          title="This is taking longer than usual"
          action={
            <Button size="sm" icon={RefreshCw} loading={checking} onClick={onCheckAgain}>
              Check again
            </Button>
          }
        >
          The queue may be busy or the analysis service restarting; it retries by itself. Check again in a minute.
        </Alert>
      ) : (
        <Alert tone="info" title={analysis.status === 'PENDING' ? 'Queued for analysis' : 'Reading your resume'}>
          This usually takes a few seconds. The page updates by itself.
        </Alert>
      )
    case 'COMPLETED':
      return (
        <Alert tone="success" title="Analysis ready">
          Read {analysis.analyzedAt ? formatDate(analysis.analyzedAt, { day: 'numeric', month: 'short', hour: 'numeric', minute: '2-digit' }) : 'just now'}. If
          something is wrong, suggest a correction on that line.
        </Alert>
      )
    case 'PARTIAL':
      return (
        <Alert tone="warning" title="Some details were read">
          Not everything could be structured automatically. Add anything missing as a claim.
        </Alert>
      )
    case 'NEEDS_OCR':
      return (
        <Alert tone="warning" title="This looks like a scanned document">
          The file has no selectable text, so details could not be read automatically. Recruiters can still open it. For automatic matching, upload a PDF
          exported from a word processor, or a .docx file.
        </Alert>
      )
    case 'UNSUPPORTED_FORMAT':
      return (
        <Alert tone="warning" title="This format cannot be analysed">
          The file is stored and attached to your applications. Upload a PDF or .docx copy for automatic matching.
        </Alert>
      )
    case 'FAILED':
      return (
        <Alert
          tone="danger"
          title="Analysis failed"
          action={
            <Button size="sm" variant="primary" icon={RefreshCw} loading={retrying} onClick={onRetry}>
              Retry analysis
            </Button>
          }
        >
          {analysisErrorText(analysis.errorCode)} Your file is stored safely.
        </Alert>
      )
    default:
      return null
  }
}

function FactGroups({ resumeId, facts }: { resumeId: number; facts: ResumeFact[] }) {
  const ref = useRef<HTMLDivElement>(null)
  useLayoutEffect(() => {
    if (ref.current) stagger(ref.current.querySelectorAll<HTMLElement>('.r-fact'))
    // First render of this resume's facts only.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  if (facts.length === 0) {
    return <p className="c-muted">No structured details were found in this resume. You can add them as claims.</p>
  }
  const known = new Set(FACT_GROUPS.flatMap(group => group.types))
  const other = facts.filter(fact => !known.has(fact.type))
  return (
    <div className="r-groups" ref={ref}>
      {FACT_GROUPS.map(group => {
        const groupFacts = facts.filter(fact => group.types.includes(fact.type))
        if (groupFacts.length === 0) return null
        return <FactGroup key={group.key} title={group.label} resumeId={resumeId} facts={groupFacts} correctable={group.correctable} />
      })}
      {other.length > 0 && <FactGroup title="Other details" resumeId={resumeId} facts={other} correctable={false} />}
    </div>
  )
}

function FactGroup({ title, resumeId, facts, correctable }: { title: string; resumeId: number; facts: ResumeFact[]; correctable: boolean }) {
  const headingId = useId()
  return (
    <section className="r-group" aria-labelledby={headingId}>
      <h3 id={headingId} className="r-group-title">
        {title} <span className="numeric">· {facts.length}</span>
      </h3>
      <ul className="c-rows" role="list">
        {facts.map(fact => (
          <FactRow key={fact.id} resumeId={resumeId} fact={fact} correctable={correctable} />
        ))}
      </ul>
    </section>
  )
}

/** The candidate's claimed value, shown only when it differs from what the parser read. */
function claimedValueOf(fact: ResumeFact): string | null {
  const candidate = fact.claimedValue ?? (fact.value !== fact.extractedValue ? fact.value : null)
  return candidate && candidate !== fact.extractedValue ? candidate : null
}

function FactRow({ resumeId, fact, correctable }: { resumeId: number; fact: ResumeFact; correctable: boolean }) {
  const queryClient = useQueryClient()
  const notify = useNotify()
  const [editing, setEditing] = useState(false)
  const claimed = claimedValueOf(fact)
  const extracted = fact.extractedValue || fact.value
  const [draft, setDraft] = useState(claimed ?? extracted)
  const [validation, setValidation] = useState<string | null>(null)
  const editRef = useRef<HTMLButtonElement>(null)

  /** Close the editor and put focus back on "Suggest a correction" (it is re-mounted, so wait a tick). */
  function closeEditor() {
    setEditing(false)
    setValidation(null)
    window.setTimeout(() => editRef.current?.focus(), 0)
  }

  const save = useMutation({
    mutationFn: (value: string) => resumesApi.suggestCorrection(resumeId, fact.id, { value }),
    onSuccess: (_updated, value) => {
      closeEditor()
      // Sending the text as read confirms the fact and removes an earlier correction (the server deletes that claim).
      if (value === extracted) notify('success', 'Correction removed', { body: 'The text read from your resume is used again.' })
      else notify('success', 'Saved as your claim', { body: 'Recruiters see it as unverified until it is checked. The text from your resume is kept.' })
      void queryClient.invalidateQueries({ queryKey: queryKeys.resumeAnalysis(resumeId) })
      void queryClient.invalidateQueries({ queryKey: queryKeys.claims })
      void queryClient.invalidateQueries({ queryKey: queryKeys.verificationLedger })
    },
  })

  function submit(event: FormEvent) {
    event.preventDefault()
    // Enter calls requestSubmit(), which a busy button cannot block: never send the same correction twice.
    if (save.isPending) return
    const value = draft.trim()
    if (!value) return setValidation('Enter the corrected value.')
    if (value.length > FACT_MAX) return setValidation(`Use at most ${FACT_MAX} characters.`)
    if (value === (claimed ?? extracted)) return setValidation('This is the same as the current value.')
    setValidation(null)
    save.mutate(value)
  }

  return (
    <li className="r-fact" data-editing={editing || undefined}>
      <div className="r-fact-main">
        <p className="r-fact-value">
          <span className="sr-only">From your resume: </span>
          {extracted}
        </p>
        {claimed && (
          <p className="r-fact-claim">
            <EvidenceLadder tier="SELF_CLAIMED" audience="candidate" animate={false} />
            <span>{claimed}</span>
          </p>
        )}
        {fact.evidence && <Quote className="r-fact-quote">{fact.evidence}</Quote>}
        <ProvenanceLine icon={ScrollText} source="Your resume" actor={fact.sourceSection ? `${humanize(fact.sourceSection)} section` : undefined} />
      </div>
      <div className="r-fact-side">
        <EvidenceLadder tier={fact.tier ?? 'RESUME_STATED'} audience="candidate" />
        {correctable && !editing && (
          <Button
            ref={editRef}
            size="sm"
            variant="ghost"
            icon={Pencil}
            onClick={() => {
              setDraft(claimed ?? extracted)
              setEditing(true)
            }}
          >
            Suggest a correction<span className="sr-only"> for {extracted}</span>
          </Button>
        )}
      </div>
      <div className="r-fact-edit" aria-hidden={!editing}>
        <div>
          {editing && (
            <form onSubmit={submit} noValidate className="r-fact-form">
              <Field label="Your correction" error={validation ?? undefined} hint="Saved as your claim. Recruiters see it as unverified until it is checked; the text read from your resume is kept as it is.">
                <Textarea
                  rows={2}
                  maxLength={FACT_MAX}
                  value={draft}
                  autoFocus
                  onChange={event => {
                    setDraft(event.target.value)
                    setValidation(null)
                  }}
                  onKeyDown={e => {
                    if (e.key === 'Enter' && !e.shiftKey) {
                      e.preventDefault()
                      e.currentTarget.form?.requestSubmit()
                    }
                    if (e.key === 'Escape') {
                      e.stopPropagation()
                      if (!save.isPending) closeEditor()
                    }
                  }}
                />
              </Field>
              {save.isError && <FormError error={errorMessage(save.error)} />}
              <div className="r-fact-actions">
                <Button
                  variant="ghost"
                  size="sm"
                  disabled={save.isPending}
                  onClick={() => {
                    save.reset()
                    closeEditor()
                  }}
                >
                  Cancel
                </Button>
                <Button type="submit" variant="primary" size="sm" loading={save.isPending}>
                  Save as my claim
                </Button>
              </div>
            </form>
          )}
        </div>
      </div>
    </li>
  )
}
