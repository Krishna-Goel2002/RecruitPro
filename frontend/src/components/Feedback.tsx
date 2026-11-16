import type { ReactNode } from 'react'
import { errorMessage } from '../api/client'
import brand from '../design/brand/active'
import { Alert, Button, Spinner } from '../design/components'
import { RefreshCw } from '../design/icons'
import './feedback.css'

/**
 * Inline error for a region that failed to load (UX X-15): what failed, the server's reason and Try again.
 * Sits where the content would have been, so the page keeps its layout.
 */
export function ErrorState({
  error,
  onRetry,
  title = 'We could not load this',
  stale,
}: {
  error: unknown
  onRetry?: () => void
  title?: string
  /** Earlier data is still on screen below. */
  stale?: boolean
}) {
  return (
    <Alert
      tone="danger"
      live
      title={title}
      className="c-error-state"
      action={
        onRetry && (
          <Button size="sm" variant="secondary" icon={RefreshCw} onClick={onRetry}>
            Try again
          </Button>
        )
      }
    >
      {errorMessage(error)}
      {stale ? ' The results below may be out of date.' : ''}
    </Alert>
  )
}

/** Form-level error, announced when it appears. Accepts a string or an error object. */
export function FormError({ error }: { error: unknown }) {
  if (!error) return null
  return (
    <Alert tone="danger" live className="c-form-error">
      {typeof error === 'string' ? error : errorMessage(error)}
    </Alert>
  )
}

/** Whole-screen wait (session restore): the brand mark and a spinner, centred, no layout jump afterwards. */
export function FullPageLoading({ label }: { label: string }) {
  return (
    <main className="c-fullpage" id="main">
      <div className="c-fullpage-inner" role="status" aria-live="polite">
        <brand.Mark size={28} />
        <span className="c-fullpage-label">
          <Spinner size={14} />
          {label}
        </span>
      </div>
    </main>
  )
}

/** A small "key: value" definition list in the design rhythm (facts rails, summaries). */
export function Facts({ items, className }: { items: { label: ReactNode; value: ReactNode }[]; className?: string }) {
  return (
    <dl className={['c-facts', className].filter(Boolean).join(' ')}>
      {items.map((item, i) => (
        <div key={i} className="c-facts-row">
          <dt>{item.label}</dt>
          <dd>{item.value}</dd>
        </div>
      ))}
    </dl>
  )
}
