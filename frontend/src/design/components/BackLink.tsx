import { createContext, useContext, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { ArrowLeft, Icon } from '../icons'
import { cx } from './cx'

/** Where "Back" goes from the current page: the logical parent, not browser history. */
export interface BackTarget {
  /** Router path including the search string (filters and tab of the parent list are kept). */
  to: string
  /** Parent name, shown as "Back to {label}". */
  label: string
  /** Router state to hand to the parent (rarely needed). */
  state?: unknown
}

/**
 * The portal's layout resolves the back target for the current route and provides it here; every PageHeader then
 * shows the same "← Back to …" control without each page wiring it. `null` means the page is a top-level destination.
 */
const BackNavContext = createContext<BackTarget | null>(null)

export function BackNavProvider({ value, children }: { value: BackTarget | null; children: ReactNode }) {
  return <BackNavContext.Provider value={value}>{children}</BackNavContext.Provider>
}

export function useBackTarget(): BackTarget | null {
  return useContext(BackNavContext)
}

/** "← Back to {label}": a quiet link-button above the page title. Alt + ← keeps working natively. */
export function BackLink({ to, label, state, className }: BackTarget & { className?: string }) {
  return (
    <Link to={to} state={state} className={cx('ui-back', className)} data-back="">
      <Icon icon={ArrowLeft} size={16} className="ui-back-icon" />
      <span>
        Back to <span className="ui-back-label">{label}</span>
      </span>
    </Link>
  )
}
