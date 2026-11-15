import { useEffect, useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { verificationApi } from '../../api/endpoints'
import { queryKeys } from '../../api/queryKeys'
import type { VerificationSource } from '../../api/types'
import { ConfirmDialog } from '../../components/Dialog'
import { EmptyState, ErrorBlock, FormError, LoadingBlock, Spinner } from '../../components/Feedback'
import { useNotify } from '../../components/Notices'
import { Section } from '../../components/PageHeader'
import { formatDateTime } from '../../lib/format'
import { usePollingWindow } from '../../lib/polling'
import {
  isRunActive,
  noOwnershipCheckReason,
  ownershipMethodLabel,
  ownershipMethodsFor,
  ownershipStatusLabel,
  runErrorText,
  runStatusLabel,
  sourceKindLabel,
} from '../../lib/verification'
import { verificationErrorMessage } from './shared'

const POLL_INTERVAL_MS = 3000
const POLL_MAX_INTERVAL_MS = 15_000

function anyRunActive(sources: VerificationSource[] | undefined): boolean {
  return !!sources?.some(source => isRunActive(source.lastRun?.status))
}

/** Connected sources with ownership state, the last run (polled while it is PENDING/RUNNING) and the actions. */
export function SourcesSection({ onCheckOwnership }: { onCheckOwnership: (sourceId: number) => void }) {
  const queryClient = useQueryClient()
  const notify = useNotify()
  const [toRevoke, setToRevoke] = useState<VerificationSource | null>(null)
  const [runError, setRunError] = useState<{ sourceId: number; message: string } | null>(null)

  // React Query may call refetchInterval while useQuery runs, before `polling` exists, so it reads a ref.
  const pollingRef = useRef<ReturnType<typeof usePollingWindow> | null>(null)
  const sources = useQuery({
    queryKey: queryKeys.verificationSources,
    queryFn: verificationApi.sources,
    refetchInterval: query => {
      const running = anyRunActive(query.state.data)
      return pollingRef.current ? pollingRef.current.nextInterval(running) : running ? POLL_INTERVAL_MS : false
    },
    refetchIntervalInBackground: false,
  })
  const active = anyRunActive(sources.data)
  const polling = usePollingWindow({ active, intervalMs: POLL_INTERVAL_MS, backoff: 1.3, maxIntervalMs: POLL_MAX_INTERVAL_MS })
  pollingRef.current = polling

  // When a run finishes, the Claim Ledger has new results.
  const wasActive = useRef(active)
  useEffect(() => {
    if (wasActive.current && !active) {
      void queryClient.invalidateQueries({ queryKey: queryKeys.verificationLedger })
      notify('info', 'A verification finished. Review the results in "Your results" below.')
    }
    wasActive.current = active
  }, [active, queryClient, notify])

  const startRun = useMutation({
    mutationFn: (sourceId: number) => verificationApi.startRun(sourceId),
    onMutate: () => setRunError(null),
    onSuccess: (run, sourceId) => {
      queryClient.setQueryData<VerificationSource[]>(queryKeys.verificationSources, current =>
        current?.map(source =>
          source.id === sourceId ? { ...source, lastRun: { id: run.id, status: run.status, finishedAt: run.finishedAt, errorCode: run.errorCode } } : source,
        ),
      )
      polling.restart()
      void queryClient.invalidateQueries({ queryKey: queryKeys.verificationSources })
      notify('success', 'Verification started. This page updates when it finishes.')
    },
    onError: (error, sourceId) => setRunError({ sourceId, message: verificationErrorMessage(error, 'run') }),
  })

  const revoke = useMutation({
    mutationFn: (sourceId: number) => verificationApi.revoke(sourceId),
    onSuccess: (_data, sourceId) => {
      setToRevoke(null)
      queryClient.setQueryData<VerificationSource[]>(queryKeys.verificationSources, current => current?.filter(source => source.id !== sourceId))
      void queryClient.invalidateQueries({ queryKey: queryKeys.verificationAll })
      notify('success', 'Source revoked. Its evidence was deleted and its results were withdrawn.')
    },
  })

  function checkAgain() {
    polling.restart()
    void sources.refetch()
  }

  return (
    <Section title="Your connected sources" id="sources" description="Each source is read only when you run a verification.">
      {sources.isPending ? (
        <LoadingBlock label="Loading your sources…" />
      ) : sources.isError ? (
        <ErrorBlock error={sources.error} onRetry={() => void sources.refetch()} />
      ) : sources.data.length === 0 ? (
        <EmptyState icon="⇄" title="No sources connected yet">
          Connect GitHub, GitLab, a website, a document or an Open Badge below.
        </EmptyState>
      ) : (
        <>
          {polling.stalled && (
            <div className="alert info block-alert" role="status">
              <div>
                <strong>Still checking</strong>
                <p>This is taking longer than usual. We stopped refreshing automatically.</p>
              </div>
              <button type="button" className="quiet-button" onClick={checkAgain}>
                Check again
              </button>
            </div>
          )}
          <ul className="plain-list card-list source-list">
            {sources.data.map(source => {
              const runActive = isRunActive(source.lastRun?.status)
              const methods = ownershipMethodsFor(source.kind)
              const reason = noOwnershipCheckReason(source.kind)
              const proven = source.ownershipStatus === 'PROVEN'
              return (
                <li key={source.id} className="claim-card source-card">
                  <div className="claim-head">
                    <span className="fact-type-badge">{sourceKindLabel(source.kind)}</span>
                    <span className={`tag ${proven ? 'green' : source.ownershipStatus === 'FAILED' ? 'amber' : ''}`}>
                      {ownershipStatusLabel(source.ownershipStatus)}
                      {proven && source.ownershipMethod ? ` · ${ownershipMethodLabel(source.ownershipMethod)}` : ''}
                    </span>
                  </div>
                  <p className="claim-value break-anywhere">{source.displayLabel || source.identifier}</p>
                  {source.displayLabel && source.displayLabel !== source.identifier && (
                    <p className="muted break-anywhere">{source.identifier}</p>
                  )}
                  {!proven && reason && <p className="muted">{reason}</p>}
                  {!proven && source.ownershipDetail && <p className="muted">Last ownership check: {source.ownershipDetail}</p>}

                  <p className="muted" aria-live="polite">
                    {runActive && <Spinner small />} Last verification: {runStatusLabel(source.lastRun?.status)}
                    {source.lastRun?.finishedAt ? ` · ${formatDateTime(source.lastRun.finishedAt)}` : ''}
                  </p>
                  {source.lastRun?.status === 'FAILED' && <p className="field-error">{runErrorText(source.lastRun.errorCode)}</p>}
                  {runError?.sourceId === source.id && <FormError error={runError.message} />}

                  <div className="claim-actions">
                    {!proven && methods.length > 0 && (
                      <button type="button" className="quiet-button" onClick={() => onCheckOwnership(source.id)}>
                        Check ownership
                      </button>
                    )}
                    <button
                      type="button"
                      className="primary"
                      disabled={runActive || (startRun.isPending && startRun.variables === source.id)}
                      onClick={() => startRun.mutate(source.id)}
                    >
                      {runActive ? 'Verification running…' : startRun.isPending && startRun.variables === source.id ? 'Starting…' : 'Run verification'}
                    </button>
                    <button type="button" className="danger-button" onClick={() => setToRevoke(source)} aria-label={`Revoke ${source.displayLabel || source.identifier}`}>
                      Revoke
                    </button>
                  </div>
                </li>
              )
            })}
          </ul>
        </>
      )}

      <ConfirmDialog
        open={toRevoke !== null}
        title="Revoke this source?"
        confirmLabel="Revoke source"
        busy={revoke.isPending}
        onCancel={() => {
          setToRevoke(null)
          revoke.reset()
        }}
        onConfirm={() => toRevoke && revoke.mutate(toRevoke.id)}
        error={<FormError error={revoke.error} />}
      >
        <p>
          We will stop reading “{toRevoke?.displayLabel || toRevoke?.identifier}”, delete the evidence collected from it and withdraw its
          results, including any you shared with employers. You can connect it again later.
        </p>
      </ConfirmDialog>
    </Section>
  )
}
