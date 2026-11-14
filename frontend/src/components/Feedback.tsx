import type { ReactNode } from 'react'
import { errorMessage } from '../api/client'

export function Spinner({ small = false }: { small?: boolean }) {
  return <span className={small ? 'spinner spinner-small' : 'spinner'} aria-hidden="true" />
}

/** Loading state for a region of the page. */
export function LoadingBlock({ label = 'Loading…' }: { label?: string }) {
  return (
    <div className="loading" role="status">
      <Spinner />
      <span>{label}</span>
    </div>
  )
}

export function FullPageLoading({ label }: { label: string }) {
  return (
    <main className="auth-shell" id="main">
      <LoadingBlock label={label} />
    </main>
  )
}

/** Error for a region, with an optional retry. */
export function ErrorBlock({ error, onRetry, title = 'We could not load this' }: { error: unknown; onRetry?: () => void; title?: string }) {
  return (
    <div className="alert error block-alert" role="alert">
      <div>
        <strong>{title}</strong>
        <p>{errorMessage(error)}</p>
      </div>
      {onRetry && (
        <button type="button" className="quiet-button" onClick={onRetry}>
          Try again
        </button>
      )}
    </div>
  )
}

/** Inline form error, announced when it appears. */
export function FormError({ error }: { error: unknown }) {
  if (!error) return null
  return (
    <p className="alert error" role="alert">
      {typeof error === 'string' ? error : errorMessage(error)}
    </p>
  )
}

export function EmptyState({ icon = '⌕', title, children, action }: { icon?: string; title: string; children?: ReactNode; action?: ReactNode }) {
  return (
    <div className="empty-state">
      <div className="empty-icon" aria-hidden="true">
        {icon}
      </div>
      <strong>{title}</strong>
      {children && <p>{children}</p>}
      {action}
    </div>
  )
}

export function Pagination({
  label,
  page,
  totalPages,
  totalElements,
  onPageChange,
  disabled = false,
}: {
  label: string
  page: number
  totalPages: number
  totalElements: number
  onPageChange: (page: number) => void
  disabled?: boolean
}) {
  if (totalPages <= 1) return null
  return (
    <nav className="pagination" aria-label={label}>
      <span className="muted">
        {totalElements} total · Page {page + 1} of {totalPages}
      </span>
      <div>
        <button type="button" className="quiet-button" disabled={disabled || page <= 0} onClick={() => onPageChange(page - 1)}>
          Previous
        </button>
        <button
          type="button"
          className="quiet-button"
          disabled={disabled || page >= totalPages - 1}
          onClick={() => onPageChange(page + 1)}
        >
          Next
        </button>
      </div>
    </nav>
  )
}
