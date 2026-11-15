import { useEffect, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { useQueryClient } from '@tanstack/react-query'
import { queryKeys } from '../api/queryKeys'
import type { VerificationSource } from '../api/types'
import { PageHeader } from '../components/PageHeader'
import { OAUTH_CONNECTED_TEXT, oauthErrorMessage, ownershipMethodsFor } from '../lib/verification'
import { ConnectSection } from './verification/ConnectSection'
import { LedgerSection } from './verification/LedgerSection'
import { OwnershipDialog } from './verification/OwnershipDialog'
import { ReferencesSection } from './verification/ReferencesSection'
import { SourcesSection } from './verification/SourcesSection'

interface ArrivalMessage {
  kind: 'success' | 'error'
  text: string
  /** The GitHub OAuth app is not configured: point to the username route. */
  suggestUsername: boolean
}

/** Reads `?connected=github` / `?error=<CODE>` left by the GitHub OAuth callback (WP-VBE notes 2.2). */
export function arrivalMessage(params: URLSearchParams): ArrivalMessage | null {
  const error = params.get('error')
  if (error) {
    return { kind: 'error', text: oauthErrorMessage(error), suggestUsername: error.toUpperCase() === 'OAUTH_NOT_CONFIGURED' }
  }
  if (params.get('connected') === 'github') return { kind: 'success', text: OAUTH_CONNECTED_TEXT, suggestUsername: false }
  return null
}

export function VerificationPage() {
  const queryClient = useQueryClient()
  const [searchParams, setSearchParams] = useSearchParams()
  const [arrival, setArrival] = useState<ArrivalMessage | null>(() => arrivalMessage(searchParams))
  const [ownershipFor, setOwnershipFor] = useState<number | null>(null)

  // Show the callback's outcome once, then clean the URL so a reload or a shared link does not repeat it.
  useEffect(() => {
    if (!searchParams.has('connected') && !searchParams.has('error')) return
    const message = arrivalMessage(searchParams)
    if (message) setArrival(message)
    if (searchParams.get('connected') === 'github') void queryClient.invalidateQueries({ queryKey: queryKeys.verificationAll })
    const next = new URLSearchParams(searchParams)
    next.delete('connected')
    next.delete('error')
    setSearchParams(next, { replace: true })
  }, [searchParams, setSearchParams, queryClient])

  function connected(source: VerificationSource) {
    queryClient.setQueryData<VerificationSource[]>(queryKeys.verificationSources, current =>
      current ? [source, ...current.filter(item => item.id !== source.id)] : [source],
    )
    void queryClient.invalidateQueries({ queryKey: queryKeys.verificationSources })
    // Sources that can prove ownership go straight to the instructions with "Check now".
    if (source.ownershipStatus !== 'PROVEN' && ownershipMethodsFor(source.kind).length > 0) setOwnershipFor(source.id)
  }

  return (
    <>
      <PageHeader
        eyebrow="CANDIDATE PORTAL"
        title="Strengthen your application"
        description="Connect places that show your work. Checkers read them, and you decide which results employers may see."
      />

      {arrival && (
        <div className={`alert ${arrival.kind === 'success' ? 'success' : 'error'} block-alert`} role={arrival.kind === 'success' ? 'status' : 'alert'}>
          <div>
            <strong>{arrival.kind === 'success' ? 'GitHub connected' : 'GitHub was not connected'}</strong>
            <p>{arrival.text}</p>
            {arrival.suggestUsername && <p>Use "Connect a source" below and choose GitHub to connect with your username.</p>}
          </div>
          <button type="button" className="quiet-button" onClick={() => setArrival(null)}>
            Dismiss
          </button>
        </div>
      )}

      <div className="panel">
        <h2 className="panel-title">How it works</h2>
        <ol className="rules">
          <li>Connect a source and agree to what will be read. You can revoke it at any time.</li>
          <li>Prove the source is yours (sign in, or publish a short code). Unproven sources can give at most "Partly verified".</li>
          <li>Run a verification. Each result names the evidence behind it.</li>
          <li>Review every result first. Nothing is shown to employers until you choose "Share with employers".</li>
        </ol>
      </div>

      <SourcesSection onCheckOwnership={setOwnershipFor} />
      <ConnectSection onConnected={connected} />
      <LedgerSection />
      <ReferencesSection />

      <OwnershipDialog sourceId={ownershipFor} onClose={() => setOwnershipFor(null)} />
    </>
  )
}
