import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { verificationApi } from '../../api/endpoints'
import { queryKeys } from '../../api/queryKeys'
import type { LedgerClaim, VerdictAction, VerdictDetail } from '../../api/types'
import { ErrorState, FormError } from '../../components/Feedback'
import { useNotify } from '../../components/Notices'
import {
  Alert, Badge, Button, CodeField, Drawer, Field, IconButton, LoadingRegion, ProvenanceLine, Quote, Skeleton, SkeletonText, Textarea, VerdictChip,
} from '../../design/components'
import { ChevronDown, ChevronUp, CircleCheck, EyeOff, Icon, KeyRound, KeySquare, RefreshCw, Users } from '../../design/icons'
import { VERDICT_DESCRIPTION, type Verdict } from '../../design/lib/labels'
import { humanize } from '../../lib/labels'
import { INTEGRITY_NOTE_INTRO, candidateStatusDescription, candidateStatusLabel, sourceKindLabel } from '../../lib/verification'
import { safeHref } from '../../lib/url'
import { kindIcon } from './model'
import { verificationErrorMessage } from './shared'

const NOTE_MAX = 1000

/** 'code-verifier' → 'code checker' (verifier ids are machine names). */
export function verifierLabel(id: string): string {
  return humanize(id.replace(/-?verifier$/i, '')).toLowerCase().trim() + ' checker'
}

export function statusTone(status: string): { tone: 'info' | 'success' | 'neutral'; variant: 'solid' | 'outline' | 'struck' } {
  if (status === 'AWAITING_CANDIDATE') return { tone: 'info', variant: 'solid' }
  if (status === 'SHARED') return { tone: 'success', variant: 'solid' }
  if (status === 'HIDDEN') return { tone: 'neutral', variant: 'outline' }
  return { tone: 'neutral', variant: 'struck' }
}

export function StatusBadge({ status }: { status: string }) {
  const { tone, variant } = statusTone(status)
  return (
    <Badge tone={tone} variant={variant} title={candidateStatusDescription(status) || undefined}>
      {candidateStatusLabel(status)}
    </Badge>
  )
}

interface VerdictDrawerProps {
  verdictId: number | null
  /** Ids in review order, for Previous / Next ("2 of 3 waiting"). */
  queue: number[]
  onNavigate: (id: number | null) => void
  onClose: () => void
}

/**
 * Result detail (§9.16 drawer, UX "Verdict detail"): the ledger stays visible behind. Why → Evidence → Notes →
 * Interviewer question → what employers will see; sticky footer Hide · Re-check · Share with employers. After a
 * decision the drawer moves on to the next result waiting for review (J / K also move).
 */
export function VerdictDrawer({ verdictId, queue, onNavigate, onClose }: VerdictDrawerProps) {
  const queryClient = useQueryClient()
  const notify = useNotify()
  const [note, setNote] = useState('')
  const [noteOpen, setNoteOpen] = useState(false)
  const [lastId, setLastId] = useState<number | null>(verdictId)
  if (verdictId !== null && verdictId !== lastId) setLastId(verdictId)
  const shownId = verdictId ?? lastId

  const detail = useQuery({
    queryKey: queryKeys.verdict(shownId ?? 0),
    queryFn: () => verificationApi.verdict(shownId as number),
    enabled: shownId !== null,
  })

  useEffect(() => {
    setNote('')
    setNoteOpen(false)
  }, [shownId])

  const position = shownId !== null ? queue.indexOf(shownId) : -1
  const prev = position > 0 ? queue[position - 1] : null
  const next = position >= 0 && position < queue.length - 1 ? queue[position + 1] : null

  // J / K move between results while the drawer is open (not while typing).
  useEffect(() => {
    if (verdictId === null) return
    const onKey = (e: KeyboardEvent) => {
      // Plain J / K only: Ctrl+K, Cmd+J etc. belong to the browser or assistive tech.
      if (e.ctrlKey || e.metaKey || e.altKey || e.defaultPrevented) return
      const t = e.target as HTMLElement | null
      if (t?.closest?.('input, textarea, select, [contenteditable]')) return
      if (e.key === 'j' && next !== null) onNavigate(next)
      if (e.key === 'k' && prev !== null) onNavigate(prev)
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [verdictId, next, prev, onNavigate])

  const respond = useMutation({
    mutationFn: (action: VerdictAction) => verificationApi.respond(shownId as number, { action, note: note.trim() || null }),
    onMutate: async action => {
      // Optimistic: the ledger row and the review list update at once; rolled back on error. A ledger refetch that is
      // already in flight (polling, focus) is cancelled first, or its older answer would overwrite the change.
      await queryClient.cancelQueries({ queryKey: queryKeys.verificationLedger })
      const previous = queryClient.getQueryData<LedgerClaim[]>(queryKeys.verificationLedger)
      if (action !== 'RECHECK') {
        const status = action === 'SHARE' ? 'SHARED' : 'HIDDEN'
        queryClient.setQueryData<LedgerClaim[]>(queryKeys.verificationLedger, current =>
          current?.map(claim => ({ ...claim, verdicts: claim.verdicts.map(v => (v.id === shownId ? { ...v, candidateStatus: status } : v)) })),
        )
      }
      return { previous }
    },
    onError: (_error, _action, context) => {
      if (context?.previous) queryClient.setQueryData(queryKeys.verificationLedger, context.previous)
    },
    onSuccess: (updated: VerdictDetail, action) => {
      queryClient.setQueryData(queryKeys.verdict(updated.id), updated)
      void queryClient.invalidateQueries({ queryKey: queryKeys.verificationLedger })
      if (action === 'RECHECK') void queryClient.invalidateQueries({ queryKey: queryKeys.verificationSources })
      notify(
        'success',
        action === 'SHARE' ? 'Shared with employers' : action === 'HIDE' ? 'Hidden from employers' : 'Re-check started',
        {
          body:
            action === 'SHARE'
              ? 'Employers you applied to can now see this result.'
              : action === 'HIDE'
                ? 'Employers cannot see this result. You can share it later.'
                : 'The new result appears here when it finishes.',
        },
      )
      // Move on to the next result still waiting, if any.
      const remaining = queue.filter(id => id !== updated.id)
      const after = remaining.find(id => queue.indexOf(id) > position) ?? remaining[0] ?? null
      if (action !== 'RECHECK' && after !== null) onNavigate(after)
    },
  })

  const data = detail.data
  const verdict = data?.verdict as Verdict | undefined
  const busy = respond.isPending
  const allDone = queue.length === 0 && data && data.candidateStatus !== 'AWAITING_CANDIDATE'

  return (
    <Drawer
      open={verdictId !== null}
      onClose={() => {
        respond.reset()
        onClose()
      }}
      title={data ? data.value : 'Result details'}
      description={data ? `${humanize(data.claimType)} · checked against ${data.sourceLabel ?? sourceKindLabel(data.sourceKind)}` : undefined}
      busy={busy}
      headerExtra={
        <div className="v-drawer-head">
          <div className="v-drawer-chips">
            {data && <VerdictChip verdict={verdict} />}
            {data && <StatusBadge status={data.candidateStatus} />}
          </div>
          {queue.length > 1 && position >= 0 && (
            <div className="v-drawer-nav">
              <span className="v-drawer-pos numeric">
                {position + 1} of {queue.length} waiting
              </span>
              <IconButton label="Previous result (K)" icon={ChevronUp} size="sm" disabled={prev === null} onClick={() => prev !== null && onNavigate(prev)} />
              <IconButton label="Next result (J)" icon={ChevronDown} size="sm" disabled={next === null} onClick={() => next !== null && onNavigate(next)} />
            </div>
          )}
        </div>
      }
      footer={
        data?.current ? (
          <div className="v-drawer-footer">
            {data.sourceKind !== 'REFERENCE' && (
              <Button variant="ghost" icon={RefreshCw} disabled={busy} onClick={() => respond.mutate('RECHECK')}>
                Re-check
              </Button>
            )}
            <span className="grow" />
            {/* Says why Hide or Share is greyed out (it already has that state; a new note re-enables it). */}
            {(data.candidateStatus === 'HIDDEN' || data.candidateStatus === 'SHARED') && !note.trim() && (
              <span className="v-meta v-drawer-why">{data.candidateStatus === 'HIDDEN' ? 'Already hidden' : 'Already shared'}. Add a note to update it.</span>
            )}
            <Button
              variant="secondary"
              icon={EyeOff}
              disabled={busy || (data.candidateStatus === 'HIDDEN' && !note.trim())}
              loading={busy && respond.variables === 'HIDE'}
              onClick={() => respond.mutate('HIDE')}
            >
              Hide
            </Button>
            <Button
              variant="primary"
              icon={Users}
              disabled={busy || (data.candidateStatus === 'SHARED' && !note.trim())}
              loading={busy && respond.variables === 'SHARE'}
              onClick={() => respond.mutate('SHARE')}
            >
              {data.candidateStatus === 'SHARED' ? 'Update note' : 'Share with employers'}
            </Button>
          </div>
        ) : undefined
      }
    >
      {detail.isPending ? (
        <LoadingRegion label="Loading the result">
          <Skeleton width="40%" height={14} />
          <div style={{ marginTop: 12 }}>
            <SkeletonText lines={4} />
          </div>
          <Skeleton width="100%" height={64} style={{ marginTop: 24 }} />
        </LoadingRegion>
      ) : detail.isError ? (
        <ErrorState error={detail.error} onRetry={() => void detail.refetch()} title="This result could not be loaded" />
      ) : data ? (
        <div className="v-detail" key={data.id}>
          {allDone && (
            <Alert tone="success" title="All results reviewed">
              Nothing else is waiting for you. Shared results are visible to employers you applied to.
            </Alert>
          )}
          {verdict && <p className="v-detail-lead">{VERDICT_DESCRIPTION[verdict]}</p>}

          <div className="v-ownership">
            <Icon icon={data.ownershipProven ? KeyRound : KeySquare} size={16} />
            <span>
              {data.ownershipProven ? 'You proved this source is yours.' : 'Ownership of this source is not proven, so results are at most "Partly verified".'}
            </span>
          </div>

          {!data.current && <Alert tone="neutral">This result was replaced or withdrawn and can no longer be shared.</Alert>}

          {data.reasons.length > 0 && (
            <section className="v-block" aria-labelledby="v-why">
              <h3 id="v-why" className="t-h3">Why</h3>
              <ul className="v-reasons">
                {data.reasons.map((reason, i) => (
                  <li key={i}>
                    <p>{reason.text}</p>
                    {reason.quote && <Quote>{reason.quote}</Quote>}
                  </li>
                ))}
              </ul>
            </section>
          )}

          {data.evidence.length > 0 && (
            <section className="v-block" aria-labelledby="v-evidence">
              <h3 id="v-evidence" className="t-h3">Evidence</h3>
              <ul className="v-evidence">
                {data.evidence.map(item => (
                  <li key={item.id}>
                    <p className="v-evidence-title">{item.title || item.url || humanize(item.kind)}</p>
                    {item.excerpt && <Quote>{item.excerpt}</Quote>}
                    <ProvenanceLine
                      icon={kindIcon(data.sourceKind)}
                      source={sourceKindLabel(data.sourceKind)}
                      actor={`retrieved by the ${verifierLabel(data.verifier)}`}
                      at={item.retrievedAt}
                      href={safeHref(item.url)}
                    />
                    <CodeField label="Fingerprint" value={item.contentHash.replace(/^sha256:/i, '')} truncate="middle" className="v-hash" />
                  </li>
                ))}
              </ul>
            </section>
          )}

          {data.integrityNotes.length > 0 && (
            <section className="v-block" aria-labelledby="v-notes">
              <h3 id="v-notes" className="t-h3">Notes for context</h3>
              <p className="v-muted">{INTEGRITY_NOTE_INTRO}</p>
              <ul className="v-list">
                {data.integrityNotes.map((n, i) => (
                  <li key={`${n.code}-${i}`}>{n.text}</li>
                ))}
              </ul>
            </section>
          )}

          {data.probeQuestion && (
            <section className="v-block" aria-labelledby="v-probe">
              <h3 id="v-probe" className="t-h3">An interviewer may ask</h3>
              <Quote large>{data.probeQuestion}</Quote>
            </section>
          )}

          <section className="v-block v-preview" aria-labelledby="v-preview">
            <h3 id="v-preview" className="t-h3">What employers see if you share</h3>
            <ul className="v-checks">
              <li>
                <Icon icon={CircleCheck} size={14} /> The result, the kind of source, whether you proved ownership, and the reasons and notes above
              </li>
              <li>
                <Icon icon={CircleCheck} size={14} /> Evidence links and your note, only after they reveal your identity
              </li>
              <li>
                <Icon icon={EyeOff} size={14} /> Nothing at all while you keep it hidden or waiting
              </li>
            </ul>
          </section>

          {data.candidateNote && (
            <p className="v-muted">
              Your note: <span className="v-note">{data.candidateNote}</span>
            </p>
          )}

          {data.current && (
            <div className="v-block">
              {noteOpen ? (
                <Field label="Note for employers" optional hint="Context, for example why a result is out of date. Shown with the result after you share it.">
                  <Textarea rows={3} maxLength={NOTE_MAX} showCount value={note} autoFocus onChange={e => setNote(e.target.value)} />
                </Field>
              ) : (
                <Button variant="link" onClick={() => setNoteOpen(true)}>
                  Add a note for employers
                </Button>
              )}
            </div>
          )}
          {respond.error && <FormError error={verificationErrorMessage(respond.error, 'respond')} />}
        </div>
      ) : null}
    </Drawer>
  )
}
