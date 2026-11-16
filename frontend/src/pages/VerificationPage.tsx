import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { verificationApi } from '../api/endpoints'
import { queryKeys } from '../api/queryKeys'
import type { LedgerClaim, SourceKind, VerificationSource } from '../api/types'
import { ConfirmDialog } from '../components/Dialog'
import { ErrorState } from '../components/Feedback'
import { useNotify } from '../components/Notices'
import {
  Alert, Button, EmptyState, EvidenceLadder, IconButton, LoadingRegion, Menu, PageHeader, Section, Skeleton, Stat, StatRow, VerdictChip,
} from '../design/components'
import {
  ArrowRight, BadgeCheck, ChevronRight, Ellipsis, Icon, KeyRound, KeySquare, MailPlus, Plus, RefreshCw, Trash2,
} from '../design/icons'
import type { Verdict } from '../design/lib/labels'
import { relativeTime } from '../design/lib/format'
import { stagger } from '../design/motion'
import { EnterList } from '../components/EnterList'
import { humanize } from '../lib/labels'
import { ledgerQuery } from '../lib/queries'
import {
  NOT_FOUND_EXPLANATION, OAUTH_CONNECTED_TEXT, isRunActive, noOwnershipCheckReason, oauthErrorMessage, ownershipMethodLabel, ownershipMethodsFor,
  runErrorText, sourceKindLabel,
} from '../lib/verification'
import { AddSourceDrawer, type AddSourceState, type AddStep } from './verification/AddSourceDrawer'
import { parseId } from '../lib/url'
import { KIND_ORDER, awaitingVerdicts, kindIcon, ledgerStats, runningSources, subjectKey, willCheck } from './verification/model'
import { ReferenceDrawer, ReferencesSection } from './verification/References'
import { verificationErrorMessage } from './verification/shared'
import { useSources, type SourcesState } from './verification/useSources'
import { StatusBadge, VerdictDrawer } from './verification/VerdictDrawer'
import './verification/verification.css'

interface ArrivalMessage {
  kind: 'success' | 'error'
  text: string
  suggestUsername: boolean
}

/** Reads `?connected=github` / `?error=<CODE>` left by the GitHub OAuth callback (WP-VBE notes 2.2). */
export function arrivalMessage(params: URLSearchParams): ArrivalMessage | null {
  const error = params.get('error')
  if (error) return { kind: 'error', text: oauthErrorMessage(error), suggestUsername: error.toUpperCase() === 'OAUTH_NOT_CONFIGURED' }
  if (params.get('connected') === 'github') return { kind: 'success', text: OAUTH_CONNECTED_TEXT, suggestUsername: false }
  return null
}

const STEPS: AddStep[] = ['choose', 'connect', 'prove', 'check']

/**
 * Strengthen your application (FLOWS-CANDIDATE §7): progress first, then results waiting for review, sources,
 * every claim with its outside checks, and references. Adding a source, reviewing results and asking for a reference
 * happen in drawers whose state lives in the URL (?add=source&step=…, ?review=<id>|first, ?ask=reference).
 */
export function VerificationPage() {
  const queryClient = useQueryClient()
  const notify = useNotify()
  const [params, setParams] = useSearchParams()
  const [arrival, setArrival] = useState<ArrivalMessage | null>(() => arrivalMessage(params))
  const ledger = useQuery(ledgerQuery)

  const patchParams = useCallback(
    (patch: Record<string, string | null>) => {
      setParams(
        current => {
          const next = new URLSearchParams(current)
          for (const [k, v] of Object.entries(patch)) {
            if (v === null) next.delete(k)
            else next.set(k, v)
          }
          return next
        },
        { replace: true },
      )
    },
    [setParams],
  )

  const sources = useSources(() => {
    notify('info', 'A check finished', {
      body: 'New results are waiting for your review.',
      action: { label: 'Review', onClick: () => patchParams({ review: 'first', add: null, step: null, kind: null, source: null }) },
    })
  })

  // ----- URL state
  const addOpen = params.get('add') === 'source'
  const stepParam = params.get('step') as AddStep | null
  const kindParam = params.get('kind')
  const addState: AddSourceState = {
    step: stepParam && STEPS.includes(stepParam) ? stepParam : 'choose',
    // Values come from the URL: anything that is not a known kind or a numeric id is ignored.
    kind: kindParam && (KIND_ORDER as string[]).includes(kindParam) ? (kindParam as SourceKind) : null,
    sourceId: parseId(params.get('source')),
  }
  const rawReview = params.get('review')
  const reviewParam = rawReview === 'first' || parseId(rawReview) !== null ? rawReview : null
  const askOpen = params.get('ask') === 'reference'

  // OAuth return: show the outcome once, clean the URL, and resume the add-source drawer on "Check".
  useEffect(() => {
    if (!params.has('connected') && !params.has('error')) return
    const message = arrivalMessage(params)
    if (message) setArrival(message)
    const connected = params.get('connected') === 'github'
    if (connected) void queryClient.invalidateQueries({ queryKey: queryKeys.verificationAll })
    patchParams({ connected: null, error: null, ...(connected ? { add: 'source', step: 'check', kind: 'GITHUB' } : {}) })
  }, [params, patchParams, queryClient])

  // The check step needs a source id: after the OAuth round trip, use the newest GitHub source.
  useEffect(() => {
    if (!addOpen || addState.step !== 'check' || addState.sourceId !== null || !sources.query.data) return
    const github = [...sources.query.data].filter(s => s.kind === (addState.kind ?? 'GITHUB')).sort((a, b) => b.createdAt.localeCompare(a.createdAt))[0]
    if (github) patchParams({ source: String(github.id) })
  }, [addOpen, addState.step, addState.sourceId, addState.kind, sources.query.data, patchParams])

  // ----- Review queue: results waiting, in reading order. `review=first` resolves to the first one.
  const [queue, setQueue] = useState<number[]>([])
  const awaiting = useMemo(() => awaitingVerdicts(ledger.data), [ledger.data])
  useEffect(() => {
    // Keep ids the candidate is stepping through even after they leave the "waiting" state, until the drawer closes.
    setQueue(current => {
      const ids = awaiting.map(v => v.id)
      if (!reviewParam) return ids
      const merged = [...current.filter(id => ids.includes(id) || id === Number(reviewParam))]
      for (const id of ids) if (!merged.includes(id)) merged.push(id)
      return merged.length ? merged : ids
    })
  }, [awaiting, reviewParam])
  const openReviewId = reviewParam === 'first' ? (awaiting[0]?.id ?? null) : reviewParam ? Number(reviewParam) : null
  useEffect(() => {
    if (reviewParam === 'first' && ledger.data) {
      if (awaiting[0]) patchParams({ review: String(awaiting[0].id) })
      else patchParams({ review: null })
    }
  }, [reviewParam, ledger.data, awaiting, patchParams])

  const reviewFirstOfSource = (label: string) => {
    const first = awaiting.find(v => v.sourceLabel === label) ?? awaiting[0]
    patchParams({ add: null, step: null, kind: null, source: null, review: first ? String(first.id) : null })
  }

  const stats = ledgerStats(ledger.data)
  const sourceList = sources.query.data ?? []
  const hasAnything = sourceList.length > 0 || stats.awaiting + stats.shared > 0

  return (
    <div className="v-page">
      <PageHeader
        title="Strengthen your application"
        description="Connect places that show your work. Checkers read them, and you decide which results employers may see."
        actions={
          <>
            <Button icon={MailPlus} onClick={() => patchParams({ ask: 'reference' })}>
              Ask for a reference
            </Button>
            <Button variant="primary" icon={Plus} onClick={() => patchParams({ add: 'source', step: 'choose', kind: null, source: null })}>
              Add a source
            </Button>
          </>
        }
      />

      {arrival && (
        <Alert
          tone={arrival.kind === 'success' ? 'success' : 'danger'}
          live
          title={arrival.kind === 'success' ? 'GitHub connected' : 'GitHub was not connected'}
          onDismiss={() => setArrival(null)}
          className="v-arrival"
        >
          {arrival.text}
          {arrival.suggestUsername && ' Choose "Add a source", then GitHub, then "Use my username instead".'}
        </Alert>
      )}

      {ledger.isPending || sources.query.isPending ? (
        <LoadingRegion label="Loading your evidence">
          <div className="v-stats-skeleton">
            {[0, 1, 2, 3].map(i => (
              <div key={i}>
                <Skeleton width={120} height={10} />
                <Skeleton width={56} height={20} style={{ marginTop: 8 }} />
              </div>
            ))}
          </div>
          <div className="v-rows" style={{ marginTop: 32 }}>
            {[0, 1, 2].map(i => (
              <div key={i} className="v-row v-row-skeleton">
                <Skeleton width={180} height={12} />
                <Skeleton width={260} height={10} />
              </div>
            ))}
          </div>
        </LoadingRegion>
      ) : ledger.isError ? (
        <ErrorState error={ledger.error} onRetry={() => void ledger.refetch()} title="Your results could not be loaded" />
      ) : sources.query.isError && !sources.query.data ? (
        // Without the sources we cannot tell a first visit from a failed load: never show "How it works" by mistake.
        <ErrorState error={sources.query.error} onRetry={() => void sources.query.refetch()} title="Your sources could not be loaded" />
      ) : !hasAnything ? (
        <HowItWorks onStart={() => patchParams({ add: 'source', step: 'choose' })} />
      ) : (
        <>
          <StatRow className="v-stats">
            <Stat label="Claims with outside evidence" value={stats.withOutside} format={n => `${Math.round(n)} of ${stats.claims}`} />
            <Stat label="Waiting for your review" value={stats.awaiting} />
            <Stat label="Shared with employers" value={stats.shared} />
            <Stat label="Sources" value={sourceList.length} />
          </StatRow>

          {awaiting.length > 0 && (
            <Section
              id="review"
              title="Waiting for your review"
              description="Only you can see these results until you share them."
              actions={
                <Button variant="primary" trailingIcon={ArrowRight} onClick={() => patchParams({ review: String(awaiting[0].id) })}>
                  Review {awaiting.length > 1 ? `all ${awaiting.length}` : 'it'}
                </Button>
              }
            >
              <EnterList className="v-rows v-review" role="list">
                {awaiting.map(v => (
                  <li key={v.id}>
                    <button type="button" className="v-row v-row-button" onClick={() => patchParams({ review: String(v.id) })}>
                      <span className="v-row-claim">
                        <span className="t-strong">{v.claim.value}</span>
                        <span className="v-meta">{humanize(v.claim.claimType)}</span>
                      </span>
                      <VerdictChip verdict={v.verdict as Verdict} />
                      <span className="v-meta v-row-source">
                        {v.sourceLabel ?? humanize(v.verifier)} · <time dateTime={v.createdAt}>{relativeTime(v.createdAt)}</time>
                      </span>
                      <Icon icon={ChevronRight} size={16} className="v-row-chevron" />
                    </button>
                  </li>
                ))}
              </EnterList>
            </Section>
          )}

          <SourcesSection sources={sources} onAdd={() => patchParams({ add: 'source', step: 'choose', kind: null, source: null })} onOpen={(step, s) => patchParams({ add: 'source', step, kind: s.kind, source: String(s.id) })} />

          <LedgerSection ledger={ledger.data} running={runningSources(sources.query.data)} onOpen={id => patchParams({ review: String(id) })} />
        </>
      )}

      <ReferencesSection onAsk={() => patchParams({ ask: 'reference' })} />

      <AddSourceDrawer
        open={addOpen}
        state={addState}
        onChange={next =>
          patchParams({
            add: 'source',
            ...(next.step !== undefined ? { step: next.step } : {}),
            ...(next.kind !== undefined ? { kind: next.kind } : {}),
            ...(next.sourceId !== undefined ? { source: next.sourceId === null ? null : String(next.sourceId) } : {}),
          })
        }
        onClose={() => patchParams({ add: null, step: null, kind: null, source: null })}
        sources={sources}
        ledger={ledger.data}
        onReview={reviewFirstOfSource}
      />
      <VerdictDrawer
        verdictId={openReviewId}
        queue={queue}
        onNavigate={id => patchParams({ review: id === null ? null : String(id) })}
        onClose={() => patchParams({ review: null })}
      />
      <ReferenceDrawer open={askOpen} onClose={() => patchParams({ ask: null })} ledger={ledger.data} />
    </div>
  )
}

/** First visit: the four steps as a sequence, and one action. */
function HowItWorks({ onStart }: { onStart: () => void }) {
  const steps = [
    { title: 'Connect a source', text: 'GitHub, GitLab, your website, a document or a badge. You agree to exactly what is read.' },
    { title: 'Prove it is yours', text: 'Sign in, or publish a short code. Unproven sources give at most "Partly verified".' },
    { title: 'Run a check', text: 'Checkers compare your claims with the source. Every result names its evidence.' },
    { title: 'Review and share', text: 'Nothing reaches employers until you choose "Share with employers".' },
  ]
  return (
    <section className="v-how" aria-labelledby="v-how-title">
      <h2 id="v-how-title" className="t-h2">How it works</h2>
      <ol className="v-how-steps">
        {steps.map((s, i) => (
          <li key={s.title}>
            <span className="v-how-num numeric" aria-hidden="true">
              {i + 1}
            </span>
            <p className="t-strong">{s.title}</p>
            <p className="v-muted">{s.text}</p>
          </li>
        ))}
      </ol>
      <Button variant="primary" icon={Plus} onClick={onStart}>
        Add your first source
      </Button>
    </section>
  )
}

// --------------------------------------------------------------------------------------------------- sources

function SourcesSection({
  sources, onAdd, onOpen,
}: { sources: SourcesState; onAdd: () => void; onOpen: (step: AddStep, source: VerificationSource) => void }) {
  const queryClient = useQueryClient()
  const notify = useNotify()
  const [toRevoke, setToRevoke] = useState<VerificationSource | null>(null)
  const list = sources.query.data ?? []

  const revoke = useMutation({
    mutationFn: (sourceId: number) => verificationApi.revoke(sourceId),
    onSuccess: (_d, sourceId) => {
      setToRevoke(null)
      queryClient.setQueryData<VerificationSource[]>(queryKeys.verificationSources, current => current?.filter(s => s.id !== sourceId))
      void queryClient.invalidateQueries({ queryKey: queryKeys.verificationAll })
      notify('success', 'Source revoked', { body: 'Its evidence was deleted and its results were withdrawn.' })
    },
  })

  const runError = sources.startRun.error ? verificationErrorMessage(sources.startRun.error, 'run') : null

  return (
    <Section
      id="sources"
      title="Sources"
      description="Each source is read only when you run a check."
      actions={
        <Button icon={Plus} onClick={onAdd}>
          Add a source
        </Button>
      }
    >
      {sources.stalled && (
        <Alert tone="info" title="Still checking" className="v-arrival" action={<Button size="sm" onClick={sources.checkAgain}>Check again</Button>}>
          This is taking longer than usual. We stopped refreshing automatically.
        </Alert>
      )}
      {list.length === 0 ? (
        <EmptyState compact icon={BadgeCheck} title="No sources connected" description="Connect one to check your claims against it." action={<Button onClick={onAdd}>Add a source</Button>} />
      ) : (
        <ul className="v-rows" role="list">
          {list.map(source => {
            const running = isRunActive(source.lastRun?.status)
            const proven = source.ownershipStatus === 'PROVEN'
            const canProve = !proven && ownershipMethodsFor(source.kind).length > 0
            const reason = noOwnershipCheckReason(source.kind)
            const starting = sources.startRun.isPending && sources.startRun.variables === source.id
            return (
              <li key={source.id} className="v-row v-source" data-running={running || undefined}>
                <span className="v-source-icon">
                  <Icon icon={kindIcon(source.kind)} size={20} />
                </span>
                <div className="v-source-main">
                  <p className="v-source-title">
                    <span className="t-strong v-break">{source.displayLabel || source.identifier}</span>
                    <span className="v-meta">{sourceKindLabel(source.kind)}</span>
                  </p>
                  <p className="v-source-own" data-proven={proven || undefined}>
                    <Icon icon={proven ? KeyRound : KeySquare} size={14} />
                    {proven
                      ? `Ownership proven${source.ownershipMethod ? ` · ${ownershipMethodLabel(source.ownershipMethod)}` : ''}`
                      : reason ?? (source.ownershipStatus === 'FAILED' ? 'Ownership check did not succeed yet' : 'Ownership not proven yet')}
                  </p>
                  <p className="v-meta" aria-live="polite">
                    {running
                      ? 'Checking now…'
                      : source.lastRun?.status === 'FAILED'
                        ? runErrorText(source.lastRun.errorCode)
                        : source.lastRun?.finishedAt
                          ? `Last checked ${relativeTime(source.lastRun.finishedAt)}`
                          : 'Not checked yet'}
                  </p>
                  {running && (
                    <span className="v-progress v-progress-inline" role="progressbar" aria-label={`Checking ${source.displayLabel}`} aria-valuetext="In progress">
                      <span />
                    </span>
                  )}
                  {runError && sources.startRun.variables === source.id && <p className="ui-field-error">{runError}</p>}
                </div>
                <div className="v-source-actions">
                  {canProve ? (
                    <Button size="sm" icon={KeyRound} onClick={() => onOpen('prove', source)}>
                      Prove ownership
                    </Button>
                  ) : (
                    <Button
                      size="sm"
                      icon={RefreshCw}
                      loading={starting}
                      disabled={running}
                      onClick={() =>
                        sources.startRun.mutate(source.id, {
                          onSuccess: () => notify('success', 'Check started', { body: 'This page updates when it finishes.' }),
                        })
                      }
                    >
                      {running ? 'Checking…' : source.lastRun ? 'Run again' : 'Run check'}
                    </Button>
                  )}
                  <Menu
                    label={`Actions for ${source.displayLabel || source.identifier}`}
                    items={[
                      ...(canProve
                        ? [{ label: running ? 'Check running…' : 'Run check without proof', icon: RefreshCw, disabled: running, onSelect: () => sources.startRun.mutate(source.id) }]
                        : []),
                      { label: 'Open in the guided panel', icon: ArrowRight, onSelect: () => onOpen(canProve ? 'prove' : 'check', source) },
                      { label: 'Revoke source…', icon: Trash2, danger: true, separated: true, onSelect: () => setToRevoke(source) },
                    ]}
                    trigger={props => <IconButton {...props} label={`More actions for ${source.displayLabel || source.identifier}`} icon={Ellipsis} size="sm" />}
                  />
                </div>
              </li>
            )
          })}
        </ul>
      )}
      <ConfirmDialog
        open={toRevoke !== null}
        title={`Revoke ${toRevoke?.displayLabel || toRevoke?.identifier || 'this source'}?`}
        confirmLabel="Revoke source"
        busy={revoke.isPending}
        error={revoke.error}
        onCancel={() => {
          setToRevoke(null)
          revoke.reset()
        }}
        onConfirm={() => toRevoke && revoke.mutate(toRevoke.id)}
      >
        <p>
          We stop reading it, delete the evidence collected from it and withdraw its results, including any you shared with employers. You can
          connect it again later.
        </p>
      </ConfirmDialog>
    </Section>
  )
}

// --------------------------------------------------------------------------------------------------- ledger

function LedgerSection({ ledger, running, onOpen }: { ledger: LedgerClaim[] | undefined; running: VerificationSource[]; onOpen: (verdictId: number) => void }) {
  const listRef = useRef<HTMLUListElement>(null)
  const staggered = useRef(false)
  useLayoutEffect(() => {
    if (staggered.current || !listRef.current || !ledger?.length) return
    staggered.current = true
    stagger(listRef.current.children as unknown as Iterable<HTMLElement>)
  }, [ledger])

  return (
    <Section id="ledger" title="All your claims" description={NOT_FOUND_EXPLANATION}>
      {!ledger || ledger.length === 0 ? (
        <EmptyState
          compact
          icon={BadgeCheck}
          title="No claims to check yet"
          description="Upload a resume or add a claim, then run a check against a source."
        />
      ) : (
        <ul ref={listRef} className="v-rows v-ledger" role="list">
          <li className="v-ledger-head" aria-hidden="true">
            <span>Claim</span>
            <span>Evidence</span>
            <span>Outside checks</span>
          </li>
          {ledger.map(claim => {
            // One chip per source: current verdicts, plus "Checking…" for running sources that will read this claim.
            const bySource = new Map<string, { verdict?: (typeof claim.verdicts)[number]; checking: boolean }>()
            for (const v of claim.verdicts) bySource.set(v.sourceLabel ?? `v${v.id}`, { verdict: v, checking: false })
            for (const s of running) {
              if (!willCheck(s, claim)) continue
              const entry = bySource.get(s.displayLabel)
              bySource.set(s.displayLabel, { verdict: entry?.verdict, checking: true })
            }
            return (
              <li key={subjectKey(claim)} className="v-ledger-row">
                <div className="v-row-claim">
                  <span className="t-strong">{claim.value}</span>
                  <span className="v-meta">
                    {humanize(claim.claimType)} · {claim.subjectType === 'RESUME_FACT' ? 'from your resume' : 'your claim'}
                  </span>
                </div>
                <div className="v-ledger-tier">
                  <EvidenceLadder tier={claim.tier} audience="candidate" />
                </div>
                <div className="v-ledger-checks">
                  {bySource.size === 0 ? (
                    <span className="v-meta">Not checked yet</span>
                  ) : (
                    // The same element stays mounted from "Checking…" to the verdict, so the chip morphs (M6).
                    [...bySource.entries()].map(([label, entry]) => {
                      const ready = !!entry.verdict && !entry.checking
                      return (
                        <button
                          key={label}
                          type="button"
                          className="v-chip-btn"
                          aria-disabled={!ready || undefined}
                          onClick={() => ready && onOpen(entry.verdict!.id)}
                        >
                          <VerdictChip verdict={entry.verdict?.verdict as Verdict | undefined} state={entry.checking ? 'checking' : 'done'} size="sm" />
                          {ready && <StatusBadge status={entry.verdict!.candidateStatus} />}
                          <span className="v-chip-src">{label}</span>
                          {ready && <span className="sr-only">. Open the result</span>}
                        </button>
                      )
                    })
                  )}
                </div>
              </li>
            )
          })}
        </ul>
      )}
    </Section>
  )
}
