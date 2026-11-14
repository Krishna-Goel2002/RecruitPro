import { useId, useState, type FormEvent } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { errorMessage } from '../../api/client'
import { resumesApi } from '../../api/endpoints'
import { queryKeys } from '../../api/queryKeys'
import type { Resume, ResumeAnalysis, ResumeFact } from '../../api/types'
import { TierBadge } from '../../components/Badges'
import { ErrorBlock, FormError, LoadingBlock, Spinner } from '../../components/Feedback'
import { useNotify } from '../../components/Notices'
import { formatDateTime } from '../../lib/format'
import { CLAIM_LABEL, FACT_GROUPS, analysisErrorText, explainFlag, humanize } from '../../lib/labels'
import type { useResumeAnalysis } from './useResumeAnalysis'

type AnalysisHook = ReturnType<typeof useResumeAnalysis>

const FACT_MAX = 1000

export function AnalysisPanel({ resume, analysis }: { resume: Resume; analysis: AnalysisHook }) {
  const { query, stalled, checkAgain } = analysis
  const notify = useNotify()

  const retry = useMutation({
    mutationFn: () => resumesApi.retryAnalysis(resume.id),
    onSuccess: () => {
      notify('success', 'Analysis queued again. This page will update when it finishes.')
      checkAgain()
    },
  })

  if (query.isPending) return <LoadingBlock label="Loading analysis…" />
  if (query.isError) return <ErrorBlock error={query.error} title="Analysis unavailable" onRetry={() => void query.refetch()} />

  const data = query.data
  return (
    <div className="analysis">
      <StatusBanner
        analysis={data}
        stalled={stalled}
        checking={query.isFetching}
        onCheckAgain={checkAgain}
        onRetry={() => retry.mutate()}
        retrying={retry.isPending}
      />
      <FormError error={retry.error} />
      {data.flags && data.flags.length > 0 && (
        <section className="flag-list" aria-label="Notes about this file">
          <h3 className="subheading">Notes about this file</h3>
          <ul className="plain-list">
            {data.flags.map((flag, index) => {
              const explanation = explainFlag(flag.code, flag.detail)
              return (
                <li key={`${flag.code}-${index}`} className="flag-item">
                  <strong>{explanation.title}</strong>
                  <p>{explanation.text}</p>
                  {flag.detail && explanation.text !== flag.detail && <p className="muted">Detail: {flag.detail}</p>}
                </li>
              )
            })}
          </ul>
        </section>
      )}
      {(data.status === 'COMPLETED' || data.status === 'PARTIAL') && <FactGroups resumeId={resume.id} facts={data.facts} />}
    </div>
  )
}

function StatusBanner({
  analysis,
  stalled,
  checking,
  onCheckAgain,
  onRetry,
  retrying,
}: {
  analysis: ResumeAnalysis
  stalled: boolean
  checking: boolean
  onCheckAgain: () => void
  onRetry: () => void
  retrying: boolean
}) {
  switch (analysis.status) {
    case 'PENDING':
    case 'PROCESSING':
      return (
        <div className="state-banner state-pending">
          <div className="state-banner-icon" aria-hidden="true">
            {stalled ? '◷' : <Spinner small />}
          </div>
          <div>
            <strong>{analysis.status === 'PENDING' ? 'Queued for analysis' : 'Reading your resume'}</strong>
            {stalled ? (
              <>
                <p>
                  This is taking longer than usual. The queue may be busy or the analysis service may be restarting; it
                  retries automatically. Check again in a minute.
                </p>
                <button type="button" className="quiet-button" onClick={onCheckAgain} disabled={checking}>
                  {checking ? 'Checking…' : 'Check again'}
                </button>
              </>
            ) : (
              <p>This usually takes a few seconds. The page updates by itself.</p>
            )}
          </div>
        </div>
      )
    case 'COMPLETED':
      return (
        <div className="state-banner state-completed">
          <div className="state-banner-icon" aria-hidden="true">
            ✓
          </div>
          <div>
            <strong>Analysis ready</strong>
            <p>
              Check what was read from your resume{analysis.analyzedAt ? ` (${formatDateTime(analysis.analyzedAt)})` : ''}. If
              something is wrong, suggest a correction.
            </p>
          </div>
        </div>
      )
    case 'PARTIAL':
      return (
        <div className="state-banner state-partial">
          <div className="state-banner-icon" aria-hidden="true">
            ℹ
          </div>
          <div>
            <strong>Some details were read</strong>
            <p>Not everything could be structured automatically. Add anything missing on the Evidence page.</p>
          </div>
        </div>
      )
    case 'NEEDS_OCR':
      return (
        <div className="state-banner state-warning">
          <div className="state-banner-icon" aria-hidden="true">
            ▧
          </div>
          <div>
            <strong>This looks like a scanned document</strong>
            <p>
              The file has no selectable text, so details could not be read automatically. Recruiters can still open the
              file. For automatic matching, upload a PDF exported from a word processor, or a .docx file.
            </p>
          </div>
        </div>
      )
    case 'UNSUPPORTED_FORMAT':
      return (
        <div className="state-banner state-warning">
          <div className="state-banner-icon" aria-hidden="true">
            !
          </div>
          <div>
            <strong>This format cannot be analysed</strong>
            <p>The file is stored and attached to your applications. Upload a PDF or .docx copy for automatic matching.</p>
          </div>
        </div>
      )
    case 'FAILED':
      return (
        <div className="state-banner state-failed">
          <div className="state-banner-icon" aria-hidden="true">
            ✕
          </div>
          <div>
            <strong>Analysis failed</strong>
            <p>{analysisErrorText(analysis.errorCode)} Your file is stored safely; you can try the analysis again.</p>
            <button type="button" className="primary small" onClick={onRetry} disabled={retrying}>
              {retrying ? 'Queuing…' : 'Retry analysis'}
            </button>
          </div>
        </div>
      )
    default:
      return null
  }
}

function FactGroups({ resumeId, facts }: { resumeId: number; facts: ResumeFact[] }) {
  if (facts.length === 0) {
    return <p className="muted">No structured details were found in this resume. You can add them as claims on the Evidence page.</p>
  }
  const known = new Set(FACT_GROUPS.flatMap(group => group.types))
  const other = facts.filter(fact => !known.has(fact.type))
  return (
    <div className="fact-groups">
      {FACT_GROUPS.map(group => {
        const groupFacts = facts.filter(fact => group.types.includes(fact.type))
        if (groupFacts.length === 0) return null
        return (
          <FactGroup key={group.key} title={group.label} resumeId={resumeId} facts={groupFacts} correctable={group.correctable} />
        )
      })}
      {other.length > 0 && <FactGroup title="Other details" resumeId={resumeId} facts={other} correctable={false} />}
    </div>
  )
}

function FactGroup({ title, resumeId, facts, correctable }: { title: string; resumeId: number; facts: ResumeFact[]; correctable: boolean }) {
  const headingId = useId()
  return (
    <section className="fact-group" aria-labelledby={headingId}>
      <h3 id={headingId} className="subheading">
        {title} <span className="tab-count">{facts.length}</span>
      </h3>
      <ul className="fact-cards-grid plain-list">
        {facts.map(fact => (
          <li key={fact.id}>
            <FactCard resumeId={resumeId} fact={fact} correctable={correctable} />
          </li>
        ))}
      </ul>
    </section>
  )
}

/**
 * The candidate's claimed value, shown only when it differs from what the parser read. `claimedValue` is the
 * reviewed value, which equals the extracted value when a fact was merely confirmed; older responses only carry
 * a `value` that differs from `extractedValue`.
 */
function claimedValueOf(fact: ResumeFact): string | null {
  const candidate = fact.claimedValue ?? (fact.value !== fact.extractedValue ? fact.value : null)
  return candidate && candidate !== fact.extractedValue ? candidate : null
}

function FactCard({ resumeId, fact, correctable }: { resumeId: number; fact: ResumeFact; correctable: boolean }) {
  const queryClient = useQueryClient()
  const notify = useNotify()
  const inputId = useId()
  const [editing, setEditing] = useState(false)
  const claimed = claimedValueOf(fact)
  const extracted = fact.extractedValue || fact.value
  const [draft, setDraft] = useState(claimed ?? extracted)
  const [validation, setValidation] = useState<string | null>(null)

  const save = useMutation({
    mutationFn: (value: string) => resumesApi.suggestCorrection(resumeId, fact.id, { value }),
    onSuccess: () => {
      setEditing(false)
      notify('success', 'Saved as your claim. Recruiters see it as unverified until they check it.')
      void queryClient.invalidateQueries({ queryKey: queryKeys.resumeAnalysis(resumeId) })
      void queryClient.invalidateQueries({ queryKey: queryKeys.claims })
    },
  })

  function submit(event: FormEvent) {
    event.preventDefault()
    const value = draft.trim()
    if (!value) return setValidation('Enter the corrected value.')
    if (value.length > FACT_MAX) return setValidation(`Use at most ${FACT_MAX} characters.`)
    if (value === (claimed ?? extracted)) return setValidation('This is the same as the current value.')
    setValidation(null)
    save.mutate(value)
  }

  return (
    <article className={`fact-card${claimed ? ' has-claim' : ''}`}>
      <div className="fact-card-head">
        <span className="fact-type-badge">{humanize(fact.type)}</span>
        <TierBadge tier={fact.tier ?? 'RESUME_STATED'} />
      </div>
      <p className="fact-value">
        <span className="visually-hidden">From your resume: </span>
        {extracted}
      </p>
      {fact.evidence && (
        <blockquote className="fact-evidence-quote">
          <span className="visually-hidden">Text found{fact.sourceSection ? ` in the ${fact.sourceSection} section` : ''}: </span>
          {fact.evidence}
        </blockquote>
      )}
      {fact.sourceSection && <p className="fact-section-tag">Section: {humanize(fact.sourceSection)}</p>}

      {claimed && !editing && (
        <div className="fact-claim">
          <span className="diff-label">{CLAIM_LABEL}</span>
          <span className="claim-text">{claimed}</span>
        </div>
      )}

      {editing ? (
        <form className="fact-edit-panel" onSubmit={submit} noValidate>
          <label htmlFor={inputId} className="fact-edit-label">
            Your correction
          </label>
          <textarea
            id={inputId}
            className="fact-textarea"
            rows={2}
            maxLength={FACT_MAX}
            value={draft}
            aria-describedby={`${inputId}-hint`}
            aria-invalid={validation ? true : undefined}
            onChange={event => {
              setDraft(event.target.value)
              setValidation(null)
            }}
          />
          <p id={`${inputId}-hint`} className="field-hint">
            {CLAIM_LABEL}. The text read from your resume is kept as it is.
          </p>
          {validation && (
            <p className="field-error" role="alert">
              {validation}
            </p>
          )}
          {save.isError && <p className="field-error" role="alert">{errorMessage(save.error)}</p>}
          <div className="fact-edit-actions">
            <button type="submit" className="primary small" disabled={save.isPending}>
              {save.isPending ? 'Saving…' : 'Save claim'}
            </button>
            <button
              type="button"
              className="quiet-button"
              onClick={() => {
                setEditing(false)
                setValidation(null)
                save.reset()
              }}
              disabled={save.isPending}
            >
              Cancel
            </button>
          </div>
        </form>
      ) : (
        correctable && (
          <button
            type="button"
            className="text-button inline"
            onClick={() => {
              setDraft(claimed ?? extracted)
              setEditing(true)
            }}
          >
            Suggest a correction<span className="visually-hidden"> for {extracted}</span>
          </button>
        )
      )}
    </article>
  )
}
