import type { ReactNode } from 'react'
import { errorMessage, isApiError } from '../api/client'
import { Button } from '../design/components'
import { CircleAlert, Icon, LockKeyhole, RefreshCw } from '../design/icons'
import './staff-ui.css'

/**
 * Inline error for a region that failed to load (Part B). Says what failed in plain words, keeps the page around it,
 * and offers Try again. A 403 reads as "not available for your role" (the server is the authority) with no retry.
 */
export function QueryError({ error, title = 'This could not be loaded', onRetry, retrying, compact, children }: {
  error: unknown
  title?: string
  onRetry?: () => void
  retrying?: boolean
  compact?: boolean
  children?: ReactNode
}) {
  const forbidden = isApiError(error, 403)
  return (
    <div className="sx-error" role="alert" data-compact={compact || undefined}>
      <Icon icon={forbidden ? LockKeyhole : CircleAlert} size={20} className="sx-error-icon" />
      <div className="sx-error-text">
        <h3 className="t-h3">{forbidden ? 'Not available for your role' : title}</h3>
        <p>{forbidden ? 'Your role does not include this. The server checks every action, whatever this page shows.' : errorMessage(error)}</p>
        {children}
        {onRetry && !forbidden && (
          <Button size="sm" icon={RefreshCw} onClick={onRetry} loading={retrying}>
            Try again
          </Button>
        )}
      </div>
    </div>
  )
}

/** A small inline error line for forms and dialogs (server message, or the joined field errors). */
export function InlineError({ error, id }: { error: unknown; id?: string }) {
  if (!error) return null
  return (
    <p className="sx-inline-error" role="alert" id={id}>
      <Icon icon={CircleAlert} size={14} />
      <span>{typeof error === 'string' ? error : errorMessage(error)}</span>
    </p>
  )
}
