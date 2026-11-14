import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { claimsApi } from '../api/endpoints'
import { queryKeys } from '../api/queryKeys'
import type { CandidateClaim } from '../api/types'
import { TierBadge } from '../components/Badges'
import { ClaimForm } from '../components/ClaimForm'
import { ConfirmDialog } from '../components/Dialog'
import { EmptyState, ErrorBlock, FormError, LoadingBlock } from '../components/Feedback'
import { useNotify } from '../components/Notices'
import { PageHeader, Section } from '../components/PageHeader'
import { focusPageHeading } from '../lib/focus'
import { formatDate } from '../lib/format'
import { CLAIM_TYPE_LABEL, TIER_DESCRIPTION, TIER_LABEL, TIER_ORDER, claimTypeFor } from '../lib/labels'

export function EvidencePage() {
  const queryClient = useQueryClient()
  const notify = useNotify()
  const claims = useQuery({ queryKey: queryKeys.claims, queryFn: claimsApi.list })
  const [toDelete, setToDelete] = useState<CandidateClaim | null>(null)

  const remove = useMutation({
    mutationFn: (id: number) => claimsApi.remove(id),
    onSuccess: () => {
      setToDelete(null)
      notify('success', 'Claim deleted.')
      void queryClient.invalidateQueries({ queryKey: queryKeys.claims }).then(focusPageHeading)
      void queryClient.invalidateQueries({ queryKey: ['application'] })
    },
  })

  const sorted = claims.data ? [...claims.data].sort((a, b) => b.createdAt.localeCompare(a.createdAt)) : []

  return (
    <>
      <PageHeader
        eyebrow="CANDIDATE PORTAL"
        title="Your evidence"
        description="Claims you add are shown to employers next to what your resume, work samples and interviews show."
      />

      <details className="panel ladder-explainer">
        <summary>How employers weigh evidence</summary>
        <ol className="ladder">
          {TIER_ORDER.map(tier => (
            <li key={tier}>
              <TierBadge tier={tier} />
              <span>
                <strong>{TIER_LABEL[tier]}.</strong> {TIER_DESCRIPTION[tier]}
              </span>
            </li>
          ))}
        </ol>
        <p className="muted">
          Rankings use verified evidence (resume or better). A claim helps a recruiter know what to check; a link to a
          certificate or portfolio makes that check quicker.
        </p>
      </details>

      <div className="detail-grid evidence-grid">
        <Section title="Add a claim" id="add-claim">
          <div className="panel">
            <ClaimForm onCreated={() => notify('success', 'Claim added. Recruiters see it as unverified until they check it.')} />
          </div>
        </Section>

        <Section title="Your claims" id="claims">
          {claims.isPending ? (
            <LoadingBlock label="Loading claims…" />
          ) : claims.isError ? (
            <ErrorBlock error={claims.error} onRetry={() => void claims.refetch()} />
          ) : sorted.length === 0 ? (
            <EmptyState icon="✓" title="No claims yet">
              Add a skill, certification or experience your resume does not show, ideally with a link.
            </EmptyState>
          ) : (
            <ul className="plain-list claim-list">
              {sorted.map(claim => (
                <li key={claim.id} className="claim-card">
                  <div className="claim-head">
                    <span className="fact-type-badge">{CLAIM_TYPE_LABEL[claimTypeFor(claim.claimType)]}</span>
                    <TierBadge tier={claim.tier} />
                  </div>
                  <p className="claim-value">{claim.value}</p>
                  {claim.evidenceUrl && (
                    <p className="claim-link">
                      <a href={claim.evidenceUrl} target="_blank" rel="noopener noreferrer nofollow">
                        {claim.evidenceUrl}
                        <span className="visually-hidden"> (opens in a new tab)</span>
                      </a>
                    </p>
                  )}
                  <p className="muted">
                    Added {formatDate(claim.createdAt)}
                    {claim.verificationStatus === 'VERIFIED' && claim.verifiedAt
                      ? ` · Confirmed by a recruiter on ${formatDate(claim.verifiedAt)}`
                      : claim.verificationStatus === 'REJECTED' && claim.verifiedAt
                        ? ` · Checked on ${formatDate(claim.verifiedAt)}; the recruiter could not confirm it`
                        : claim.verifiedAt
                          ? ` · Checked by a recruiter on ${formatDate(claim.verifiedAt)}`
                          : ' · Not checked yet'}
                  </p>
                  {claim.verificationNote && <p className="verification-note">Recruiter note: {claim.verificationNote}</p>}
                  <div className="claim-actions">
                    <button
                      type="button"
                      className="danger-button"
                      onClick={() => setToDelete(claim)}
                      aria-label={`Delete claim: ${claim.value}`}
                    >
                      Delete
                    </button>
                  </div>
                </li>
              ))}
            </ul>
          )}
        </Section>
      </div>

      <ConfirmDialog
        open={toDelete !== null}
        title="Delete this claim?"
        confirmLabel="Delete claim"
        busy={remove.isPending}
        onCancel={() => {
          setToDelete(null)
          remove.reset()
        }}
        onConfirm={() => toDelete && remove.mutate(toDelete.id)}
        error={<FormError error={remove.error} />}
      >
        <p>
          “{toDelete?.value}” will no longer be shown to employers. Applications are re-checked without it.
        </p>
      </ConfirmDialog>
    </>
  )
}
