import type { ReactNode } from 'react'
import { errorMessage, isApiError } from '../api/client'
import { BriefcaseIcon } from './Icons'

export function FullPageLoader({ label }: { label: string }) {
  return (
    <div className="full-page-loader" role="status" aria-live="polite">
      <span className="spinner dark-spinner" aria-hidden="true" />
      <p>{label}</p>
    </div>
  )
}

export function LoadingPanel({ label = 'Loading…', compact = false }: { label?: string; compact?: boolean }) {
  return (
    <div className={`state-panel${compact ? ' compact' : ''}`} role="status" aria-live="polite">
      <span className="spinner dark-spinner" aria-hidden="true" />
      <p>{label}</p>
    </div>
  )
}

export function ErrorPanel({ error, title = 'Something went wrong', onRetry, compact = false }: {
  error: unknown
  title?: string
  onRetry?: () => void
  compact?: boolean
}) {
  const forbidden = isApiError(error, 403)
  return (
    <div className={`state-panel error-state${compact ? ' compact' : ''}`} role="alert">
      <div className="state-icon" aria-hidden="true">!</div>
      <h3>{forbidden ? 'Not available for your role' : title}</h3>
      <p>{errorMessage(error)}</p>
      {onRetry && !forbidden && <button type="button" className="outline-button" onClick={onRetry}>Try again</button>}
    </div>
  )
}

export function EmptyPanel({ title, children, action, compact = false }: {
  title: string
  children?: ReactNode
  action?: ReactNode
  compact?: boolean
}) {
  return (
    <div className={`state-panel empty-state${compact ? ' compact' : ''}`}>
      <span className="empty-icon" aria-hidden="true"><BriefcaseIcon /></span>
      <h3>{title}</h3>
      {children && <p>{children}</p>}
      {action}
    </div>
  )
}

/** Inline error for a form or section. */
export function FormError({ error, id }: { error: unknown; id?: string }) {
  if (!error) return null
  return <div className="form-api-error" role="alert" id={id}>{typeof error === 'string' ? error : errorMessage(error)}</div>
}
