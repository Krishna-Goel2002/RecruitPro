import { useLayoutEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { claimsApi } from '../api/endpoints'
import { queryKeys } from '../api/queryKeys'
import type { CandidateClaim } from '../api/types'
import { ClaimForm } from '../components/ClaimForm'
import { ConfirmDialog } from '../components/Dialog'
import { ErrorState } from '../components/Feedback'
import { useNotify } from '../components/Notices'
import {
  Button, Drawer, EmptyState, EvidenceLadder, IconButton, LoadingRegion, Menu, PageHeader, Section, Skeleton, buttonProps,
} from '../design/components'
import { ArrowUpRight, BadgeCheck, Ellipsis, Icon, Plus, ShieldCheck, Trash2 } from '../design/icons'
import { formatDate } from '../design/lib/format'
import { stagger } from '../design/motion'
import { focusPageHeading } from '../lib/focus'
import { CLAIM_TYPE_LABEL, TIER_DESCRIPTION, TIER_ORDER, claimTypeFor } from '../lib/labels'
import { claimsQuery } from '../lib/queries'
import { isHttpUrl } from '../lib/url'
import './common.css'
import './claims/claims.css'

function reviewState(claim: CandidateClaim): string {
  if (claim.verificationStatus === 'VERIFIED' && claim.verifiedAt) return `Confirmed by a recruiter on ${formatDate(claim.verifiedAt)}`
  if (claim.verificationStatus === 'REJECTED' && claim.verifiedAt) return `Checked on ${formatDate(claim.verifiedAt)}; the recruiter could not confirm it`
  if (claim.verifiedAt) return `Checked by a recruiter on ${formatDate(claim.verifiedAt)}`
  return 'Not checked yet'
}

/**
 * Claims (REDESIGN_PLAN A8): the evidence ladder always in view, claims as rows with a ladder chip, Add in a drawer
 * (optimistic insert), Delete in a row menu with the confirmation kept (applications are re-checked without it).
 */
export function EvidencePage() {
  const queryClient = useQueryClient()
  const notify = useNotify()
  const claims = useQuery(claimsQuery)
  const [adding, setAdding] = useState(false)
  const [toDelete, setToDelete] = useState<CandidateClaim | null>(null)
  const [fresh, setFresh] = useState<number | null>(null)
  const [claimDirty, setClaimDirty] = useState(false)
  const [confirmDiscard, setConfirmDiscard] = useState(false)
  // Escape, the backdrop and Cancel used to throw away a half-written claim without a word.
  const closeAdd = () => (claimDirty ? setConfirmDiscard(true) : setAdding(false))
  const listRef = useRef<HTMLUListElement>(null)
  const played = useRef(false)

  const sorted = claims.data ? [...claims.data].sort((a, b) => b.createdAt.localeCompare(a.createdAt)) : []

  useLayoutEffect(() => {
    if (played.current || !listRef.current || sorted.length === 0) return
    played.current = true
    stagger(listRef.current.children as unknown as Iterable<HTMLElement>)
  }, [sorted.length])

  const remove = useMutation({
    mutationFn: (id: number) => claimsApi.remove(id),
    onSuccess: (_d, id) => {
      setToDelete(null)
      queryClient.setQueryData<CandidateClaim[]>(queryKeys.claims, current => current?.filter(c => c.id !== id))
      notify('success', 'Claim deleted', { body: 'Applications are re-checked without it.' })
      void queryClient.invalidateQueries({ queryKey: queryKeys.claims }).then(focusPageHeading)
      void queryClient.invalidateQueries({ queryKey: ['application'] })
      void queryClient.invalidateQueries({ queryKey: queryKeys.verificationLedger })
      // A claim made from a resume correction is gone: the fact is unreviewed again (and its row loses "Your claim").
      void queryClient.invalidateQueries({ queryKey: ['resume-analysis'] })
    },
  })

  return (
    <>
      <PageHeader
        title="Claims"
        description="Things you can do that your resume does not show. Employers see each claim next to the evidence behind it."
        actions={
          <Button variant="primary" icon={Plus} onClick={() => setAdding(true)}>
            Add a claim
          </Button>
        }
      />

      <section className="cl-ladder" aria-labelledby="cl-ladder-title">
        <h2 id="cl-ladder-title" className="cl-ladder-title">
          How employers weigh evidence
        </h2>
        <ol className="cl-legend">
          {TIER_ORDER.map(tier => (
            <li key={tier}>
              <EvidenceLadder tier={tier} audience="candidate" />
              <span>{TIER_DESCRIPTION[tier]}</span>
            </li>
          ))}
        </ol>
        <p className="c-meta">Rankings use evidence from your resume or better. A link to a certificate or portfolio makes a recruiter's check quicker.</p>
      </section>

      <Section title="Your claims" id="claims" description={sorted.length > 0 ? 'Newest first.' : undefined}>
        {claims.isPending ? (
          <LoadingRegion label="Loading claims">
            {[0, 1, 2].map(i => (
              <div key={i} className="c-skel-row">
                <Skeleton width={260} height={12} />
                <Skeleton width={180} height={10} />
              </div>
            ))}
          </LoadingRegion>
        ) : claims.isError ? (
          <ErrorState error={claims.error} onRetry={() => void claims.refetch()} title="Your claims could not be loaded" />
        ) : sorted.length === 0 ? (
          <EmptyState
            icon={ShieldCheck}
            title="No claims yet"
            description="Add a skill, certification or experience your resume does not show, ideally with a link someone can open."
            action={
              <Button icon={Plus} onClick={() => setAdding(true)}>
                Add a claim
              </Button>
            }
          />
        ) : (
          <ul ref={listRef} className="c-rows" data-closed="">
            {sorted.map(claim => (
              <li key={claim.id} className="cl-row" data-fresh={claim.id === fresh || undefined}>
                <div className="cl-main">
                  <p className="cl-value">{claim.value}</p>
                  <p className="c-meta-line">
                    <span>{CLAIM_TYPE_LABEL[claimTypeFor(claim.claimType)]}</span>
                    <span>Added {formatDate(claim.createdAt)}</span>
                    <span>{reviewState(claim)}</span>
                  </p>
                  {claim.evidenceUrl && !isHttpUrl(claim.evidenceUrl) && <p className="c-meta c-break">{claim.evidenceUrl}</p>}
                  {isHttpUrl(claim.evidenceUrl) && (
                    <a className="cl-link" href={claim.evidenceUrl} target="_blank" rel="noopener noreferrer nofollow">
                      {claim.evidenceUrl.replace(/^https?:\/\//, '')}
                      <Icon icon={ArrowUpRight} size={12} />
                      <span className="sr-only"> (opens in a new tab)</span>
                    </a>
                  )}
                </div>
                <div className="cl-side">
                  <EvidenceLadder tier={claim.tier} audience="candidate" />
                  {claim.tier === 'SELF_CLAIMED' ? (
                    <Link {...buttonProps('ghost', 'sm')} to="/verification?add=source&step=choose">
                      <Icon icon={BadgeCheck} size={14} />
                      Verify with a source<span className="sr-only">: {claim.value}</span>
                    </Link>
                  ) : (
                    <span aria-hidden="true" />
                  )}
                  <Menu
                    label={`Actions for ${claim.value}`}
                    items={[{ label: 'Delete claim…', icon: Trash2, danger: true, onSelect: () => setToDelete(claim) }]}
                    trigger={props => <IconButton {...props} label={`More actions for ${claim.value}`} icon={Ellipsis} size="sm" />}
                  />
                </div>
              </li>
            ))}
          </ul>
        )}
      </Section>

      <Drawer open={adding} onClose={closeAdd} title="Add a claim" description="It is shown to employers as your claim until a recruiter or a source checks it.">
        {adding && (
          <ClaimForm
            onDirtyChange={setClaimDirty}
            onCancel={closeAdd}
            onCreated={claim => {
              setAdding(false)
              setFresh(claim.id)
              notify('success', 'Claim added', { body: 'Recruiters see it as unverified until they check it.' })
            }}
          />
        )}
      </Drawer>

      <ConfirmDialog
        open={confirmDiscard}
        title="Discard this claim?"
        confirmLabel="Discard claim"
        onCancel={() => setConfirmDiscard(false)}
        onConfirm={() => {
          setConfirmDiscard(false)
          setAdding(false)
        }}
      >
        <p>What you typed has not been added yet and will be lost.</p>
      </ConfirmDialog>

      <ConfirmDialog
        open={toDelete !== null}
        title="Delete this claim?"
        confirmLabel="Delete claim"
        busy={remove.isPending}
        error={remove.error}
        onCancel={() => {
          setToDelete(null)
          remove.reset()
        }}
        onConfirm={() => toDelete && remove.mutate(toDelete.id)}
      >
        <p>“{toDelete?.value}” will no longer be shown to employers. Applications are re-checked without it.</p>
      </ConfirmDialog>
    </>
  )
}
