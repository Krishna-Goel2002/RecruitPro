import { useEffect, useId, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { verificationApi } from '../../api/endpoints'
import { queryKeys } from '../../api/queryKeys'
import type { OwnershipCheckMethod, VerificationSource } from '../../api/types'
import { CopyField } from '../../components/CopyButton'
import { Dialog } from '../../components/Dialog'
import { ErrorBlock, FormError, LoadingBlock } from '../../components/Feedback'
import { useNotify } from '../../components/Notices'
import { challengeSteps, ownershipStatusLabel, sourceKindLabel } from '../../lib/verification'
import { verificationErrorMessage } from './shared'

/** Instructions to prove a source is yours (code to publish, with copy buttons) and "Check now". */
export function OwnershipDialog({ sourceId, onClose }: { sourceId: number | null; onClose: () => void }) {
  const queryClient = useQueryClient()
  const notify = useNotify()
  const radioName = useId()
  const sources = useQuery({
    queryKey: queryKeys.verificationSources,
    queryFn: verificationApi.sources,
    enabled: sourceId !== null,
  })
  const source = sources.data?.find(item => item.id === sourceId) ?? null
  const steps = source ? challengeSteps(source.kind, source.identifier, source.challengeCode) : []
  const [method, setMethod] = useState<OwnershipCheckMethod | null>(null)
  const [outcome, setOutcome] = useState<VerificationSource | null>(null)

  // A new source (or a different one) starts on its first method with no previous outcome.
  useEffect(() => {
    setMethod(null)
    setOutcome(null)
  }, [sourceId])

  const selected = steps.find(step => step.method === method) ?? steps[0] ?? null

  const check = useMutation({
    mutationFn: (chosen: OwnershipCheckMethod) => verificationApi.checkOwnership(sourceId as number, chosen),
    onMutate: () => setOutcome(null),
    onSuccess: updated => {
      setOutcome(updated)
      queryClient.setQueryData<VerificationSource[]>(queryKeys.verificationSources, current =>
        current?.map(item => (item.id === updated.id ? updated : item)),
      )
      if (updated.ownershipStatus === 'PROVEN') notify('success', 'Ownership proven. You can now run a verification.')
    },
  })

  function close() {
    check.reset()
    onClose()
  }

  const proven = (outcome ?? source)?.ownershipStatus === 'PROVEN'

  return (
    <Dialog
      open={sourceId !== null}
      title={source ? `Prove ${sourceKindLabel(source.kind)} is yours` : 'Prove ownership'}
      onClose={close}
      busy={check.isPending}
      size="large"
      description={source ? <span className="break-anywhere">{source.displayLabel || source.identifier}</span> : undefined}
    >
      {sources.isPending ? (
        <LoadingBlock label="Loading…" />
      ) : sources.isError ? (
        <ErrorBlock error={sources.error} onRetry={() => void sources.refetch()} />
      ) : !source ? (
        <p className="muted">This source is no longer connected.</p>
      ) : proven ? (
        <div className="stack-form">
          <p className="alert success" role="status">
            Ownership proven. Results from this source can now be “Verified”. You can remove the code again.
          </p>
          <div className="dialog-actions">
            <button type="button" className="primary" onClick={close}>
              Done
            </button>
          </div>
        </div>
      ) : steps.length === 0 ? (
        <div className="stack-form">
          {source.ownershipInstructions.length > 0 ? (
            <ul>
              {source.ownershipInstructions.map(line => (
                <li key={line}>{line}</li>
              ))}
            </ul>
          ) : (
            <p className="muted">This kind of source has no ownership check.</p>
          )}
          <div className="dialog-actions">
            <button type="button" className="quiet-button" onClick={close}>
              Close
            </button>
          </div>
        </div>
      ) : (
        <form
          className="stack-form"
          onSubmit={event => {
            event.preventDefault()
            if (selected) check.mutate(selected.method)
          }}
        >
          <p className="muted">Status: {ownershipStatusLabel(source.ownershipStatus)}</p>
          {steps.length > 1 && (
            <fieldset className="plain-fieldset">
              <legend>Choose one way to prove it</legend>
              <div className="choice-list">
                {steps.map(step => (
                  <label key={step.method} className={`choice${selected?.method === step.method ? ' selected' : ''}`}>
                    <input type="radio" name={radioName} checked={selected?.method === step.method} onChange={() => setMethod(step.method)} />
                    <span>
                      <strong>{step.title}</strong>
                    </span>
                  </label>
                ))}
              </div>
            </fieldset>
          )}
          {selected && (
            <div className="panel ownership-steps">
              <h3 className="subheading">{selected.title}</h3>
              <ol>
                {selected.steps.map(line => (
                  <li key={line}>{line}</li>
                ))}
              </ol>
              {selected.copy.map(item => (
                <CopyField key={item.label} label={item.label} value={item.value} />
              ))}
            </div>
          )}
          {source.kind === 'GITHUB' && <p className="muted">You can also prove it by connecting again with “Sign in with GitHub”.</p>}

          {outcome && outcome.ownershipStatus !== 'PROVEN' && (
            <p className="alert warning" role="alert">
              {outcome.ownershipStatus === 'PENDING'
                ? 'The check is still in progress. Try again in a minute.'
                : `We could not find the code yet${outcome.ownershipDetail ? `: ${outcome.ownershipDetail}` : '.'} Changes can take a few minutes to appear.`}
            </p>
          )}
          {check.error && <FormError error={verificationErrorMessage(check.error, 'ownership')} />}

          <div className="dialog-actions">
            <button type="button" className="quiet-button" onClick={close} disabled={check.isPending}>
              Later
            </button>
            <button type="submit" className="primary" disabled={check.isPending || !selected}>
              {check.isPending ? 'Checking…' : 'Check now'}
            </button>
          </div>
        </form>
      )}
    </Dialog>
  )
}
