import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { verificationApi } from '../../api/endpoints'
import { queryKeys } from '../../api/queryKeys'
import type { VerdictAction, VerdictDetail } from '../../api/types'
import { TierBadge } from '../../components/Badges'
import { Dialog } from '../../components/Dialog'
import { EmptyState, ErrorBlock, FormError, LoadingBlock } from '../../components/Feedback'
import { Field } from '../../components/Form'
import { useNotify } from '../../components/Notices'
import { Section } from '../../components/PageHeader'
import { CandidateStatusTag, VerdictChip } from '../../components/VerdictChip'
import { formatDate, formatDateTime } from '../../lib/format'
import { humanize } from '../../lib/labels'
import { INTEGRITY_NOTE_INTRO, NOT_FOUND_EXPLANATION, shortHash, sourceKindLabel } from '../../lib/verification'
import { verificationErrorMessage } from './shared'

const NOTE_MAX = 1000

/** The Claim Ledger: each claim with its tier and the current verdicts; a verdict opens its detail. */
export function LedgerSection() {
  const ledger = useQuery({ queryKey: queryKeys.verificationLedger, queryFn: verificationApi.ledger })
  const [openVerdict, setOpenVerdict] = useState<number | null>(null)

  return (
    <Section
      title="Your results"
      id="ledger"
      description="Every claim from your resume and evidence, with what the checkers found. Only results you share are visible to employers."
    >
      <p className="muted">{NOT_FOUND_EXPLANATION}</p>
      {ledger.isPending ? (
        <LoadingBlock label="Loading your results…" />
      ) : ledger.isError ? (
        <ErrorBlock error={ledger.error} onRetry={() => void ledger.refetch()} />
      ) : ledger.data.length === 0 ? (
        <EmptyState icon="✓" title="No claims to check yet">
          Upload a resume or add a claim on the Evidence page, then connect a source and run a verification.
        </EmptyState>
      ) : (
        <ul className="plain-list card-list ledger-list">
          {ledger.data.map(claim => (
            <li key={`${claim.subjectType}:${claim.subjectId}`} className="claim-card">
              <div className="claim-head">
                <span className="fact-type-badge">{humanize(claim.claimType)}</span>
                <TierBadge tier={claim.tier} />
              </div>
              <p className="claim-value">{claim.value}</p>
              {claim.verdicts.length === 0 ? (
                <p className="muted">Not checked yet.</p>
              ) : (
                <ul className="plain-list verdict-list" aria-label={`Results for ${claim.value}`}>
                  {claim.verdicts.map(verdict => (
                    <li key={verdict.id}>
                      <button type="button" className="verdict-row" onClick={() => setOpenVerdict(verdict.id)}>
                        <VerdictChip verdict={verdict.verdict} />
                        <span className="muted">{verdict.sourceLabel ?? humanize(verdict.verifier)}</span>
                        <CandidateStatusTag status={verdict.candidateStatus} />
                        <span className="visually-hidden">, open details</span>
                      </button>
                    </li>
                  ))}
                </ul>
              )}
            </li>
          ))}
        </ul>
      )}
      <VerdictDialog verdictId={openVerdict} onClose={() => setOpenVerdict(null)} />
    </Section>
  )
}

function VerdictDialog({ verdictId, onClose }: { verdictId: number | null; onClose: () => void }) {
  const queryClient = useQueryClient()
  const notify = useNotify()
  const [note, setNote] = useState('')
  const detail = useQuery({
    queryKey: queryKeys.verdict(verdictId ?? 0),
    queryFn: () => verificationApi.verdict(verdictId as number),
    enabled: verdictId !== null,
  })

  const respond = useMutation({
    mutationFn: (action: VerdictAction) => verificationApi.respond(verdictId as number, { action, note: note.trim() || null }),
    onSuccess: (updated: VerdictDetail, action) => {
      queryClient.setQueryData(queryKeys.verdict(updated.id), updated)
      void queryClient.invalidateQueries({ queryKey: queryKeys.verificationLedger })
      if (action === 'RECHECK') void queryClient.invalidateQueries({ queryKey: queryKeys.verificationSources })
      setNote('')
      notify(
        'success',
        action === 'SHARE'
          ? 'Shared. Employers you applied to can now see this result.'
          : action === 'HIDE'
            ? 'Hidden. Employers cannot see this result.'
            : 'Re-check started. The new result will appear here when it finishes.',
      )
    },
  })

  function close() {
    respond.reset()
    setNote('')
    onClose()
  }

  const data = detail.data

  return (
    <Dialog open={verdictId !== null} title="Result details" onClose={close} busy={respond.isPending} size="large">
      {detail.isPending ? (
        <LoadingBlock label="Loading the result…" />
      ) : detail.isError ? (
        <ErrorBlock error={detail.error} onRetry={() => void detail.refetch()} />
      ) : data ? (
        <div className="stack-form verdict-detail">
          <div className="claim-head">
            <VerdictChip verdict={data.verdict} describe />
            <CandidateStatusTag status={data.candidateStatus} />
          </div>
          <p className="claim-value">
            <span className="muted">{humanize(data.claimType)}:</span> {data.value}
          </p>
          <dl className="summary-list">
            <div>
              <dt>Source</dt>
              <dd className="break-anywhere">{data.sourceLabel ?? sourceKindLabel(data.sourceKind)}</dd>
            </div>
            <div>
              <dt>Ownership</dt>
              <dd>{data.ownershipProven ? 'Proven' : 'Not proven (results are at most “Partly verified”)'}</dd>
            </div>
            <div>
              <dt>Checked</dt>
              <dd>{formatDateTime(data.createdAt)}</dd>
            </div>
          </dl>
          {!data.current && <p className="alert info">This result was replaced or withdrawn and can no longer be shared.</p>}

          {data.reasons.length > 0 && (
            <div>
              <h3 className="subheading">Why</h3>
              <ul>
                {data.reasons.map((reason, index) => (
                  <li key={index}>
                    {reason.text}
                    {reason.quote && <blockquote className="fact-evidence-quote">“{reason.quote}”</blockquote>}
                  </li>
                ))}
              </ul>
            </div>
          )}

          {data.evidence.length > 0 && (
            <div>
              <h3 className="subheading">Evidence</h3>
              <ul className="plain-list compact-list">
                {data.evidence.map(item => (
                  <li key={item.id}>
                    <strong className="break-anywhere">{item.title || item.url || humanize(item.kind)}</strong>
                    {item.url && (
                      <div>
                        <a href={item.url} target="_blank" rel="noopener noreferrer nofollow" className="break-anywhere">
                          {item.url}
                          <span className="visually-hidden"> (opens in a new tab)</span>
                        </a>
                      </div>
                    )}
                    <small className="muted">
                      Retrieved {formatDateTime(item.retrievedAt)} · fingerprint <code title={item.contentHash}>{shortHash(item.contentHash)}</code>
                    </small>
                    {item.excerpt && <p className="muted">“{item.excerpt}”</p>}
                  </li>
                ))}
              </ul>
            </div>
          )}

          {data.integrityNotes.length > 0 && (
            <div>
              <h3 className="subheading">Notes for context</h3>
              <p className="muted">{INTEGRITY_NOTE_INTRO}</p>
              <ul>
                {data.integrityNotes.map((integrityNote, index) => (
                  <li key={`${integrityNote.code}-${index}`}>{integrityNote.text}</li>
                ))}
              </ul>
            </div>
          )}

          {data.probeQuestion && (
            <div>
              <h3 className="subheading">An interviewer may ask</h3>
              <p>{data.probeQuestion}</p>
            </div>
          )}

          {data.candidateNote && (
            <p className="muted">
              Your note{data.candidateRespondedAt ? ` (${formatDate(data.candidateRespondedAt)})` : ''}: {data.candidateNote}
            </p>
          )}

          {data.current && (
            <>
              <Field label="Note for employers" optional hint="Add context, for example why a result is out of date. Shown with the result after you share it.">
                {control => <textarea {...control} rows={3} maxLength={NOTE_MAX} value={note} onChange={event => setNote(event.target.value)} />}
              </Field>
              {respond.error && <FormError error={verificationErrorMessage(respond.error, 'respond')} />}
              <div className="dialog-actions">
                {data.sourceKind !== 'REFERENCE' && (
                  <button type="button" className="quiet-button" disabled={respond.isPending} onClick={() => respond.mutate('RECHECK')}>
                    Re-check
                  </button>
                )}
                <button type="button" className="quiet-button" disabled={respond.isPending || (data.candidateStatus === 'HIDDEN' && !note.trim())}onClick={() => respond.mutate('HIDE')}>
                  Hide
                </button>
                <button type="button" className="primary" disabled={respond.isPending || (data.candidateStatus === 'SHARED' && !note.trim())}onClick={() => respond.mutate('SHARE')}>
                  {respond.isPending ? 'Saving…' : 'Share with employers'}
                </button>
              </div>
            </>
          )}
        </div>
      ) : null}
    </Dialog>
  )
}
