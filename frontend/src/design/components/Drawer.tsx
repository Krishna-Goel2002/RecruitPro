import { useId, useRef, type ReactNode } from 'react'
import { X } from '../icons'
import { cx } from './cx'
import { useModal } from './Dialog'
import { IconButton } from './IconButton'

export interface DrawerProps {
  open: boolean
  onClose: () => void
  title: ReactNode
  description?: ReactNode
  /** md = min(480 px, 100vw) · lg = 640 px */
  size?: 'md' | 'lg'
  footer?: ReactNode
  busy?: boolean
  /** Extra header content under the title (tabs, a status line). */
  headerExtra?: ReactNode
  children?: ReactNode
  className?: string
}

/**
 * Drawer (§9.16, M9): a right-hand native <dialog> with sticky header and footer and a scrolling body; on phones it
 * becomes a bottom sheet (90 vh, top corners rounded, decorative grab handle). The list behind stays visible.
 */
export function Drawer({ open, onClose, title, description, size = 'md', footer, busy = false, headerExtra, children, className }: DrawerProps) {
  const ref = useRef<HTMLDialogElement>(null)
  const id = useId()
  const { requestClose, onBackdrop } = useModal(ref, open, onClose, busy, 200)
  return (
    <dialog
      ref={ref}
      className={cx('ui-drawer', className)}
      data-size={size}
      aria-labelledby={`${id}-t`}
      aria-describedby={description ? `${id}-d` : undefined}
      aria-busy={busy || undefined}
      onClick={onBackdrop}
    >
      <div className="ui-drawer-panel">
        <span className="ui-drawer-handle" aria-hidden="true" />
        <header className="ui-drawer-header">
          <div className="ui-dialog-titles">
            <h2 id={`${id}-t`} className="t-h3">
              {title}
            </h2>
            {description && (
              <p id={`${id}-d`} className="ui-dialog-desc">
                {description}
              </p>
            )}
          </div>
          <IconButton label="Close" icon={X} size="sm" onClick={requestClose} disabled={busy} noTooltip />
          {headerExtra && <div className="ui-drawer-header-extra">{headerExtra}</div>}
        </header>
        <div className="ui-drawer-body">{children}</div>
        {footer && <footer className="ui-drawer-footer">{footer}</footer>}
      </div>
    </dialog>
  )
}
