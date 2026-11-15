import { cloneElement, isValidElement, useCallback, useEffect, useId, useRef, useState, type ReactElement, type ReactNode } from 'react'
import { createPortal } from 'react-dom'
import { cx } from './cx'
import { portalTarget, useFloating, type Align, type Side } from './position'

export interface TooltipProps {
  content: ReactNode
  /** One focusable element (button, link). It gets aria-describedby, unless `labelOnly`. */
  children: ReactElement
  side?: Side
  align?: Align
  /** ms before showing on hover (focus shows immediately). Default 400. */
  delay?: number
  /** The tooltip repeats the element's accessible name (icon buttons): do not add aria-describedby. */
  labelOnly?: boolean
  disabled?: boolean
  className?: string
}

/**
 * Tooltip (§9.18): shown on hover and focus, hidden on Escape, hoverable (WCAG 1.4.13), never essential content.
 * Light theme: inverted (n-900); dark theme: overlay surface.
 */
export function Tooltip({ content, children, side = 'top', align = 'center', delay = 400, labelOnly, disabled, className }: TooltipProps) {
  const id = useId()
  const anchorRef = useRef<HTMLSpanElement>(null)
  const [tip, setTip] = useState<HTMLDivElement | null>(null)
  const [open, setOpen] = useState(false)
  const timer = useRef<number>()
  const { style, side: placed } = useFloating(anchorRef.current, tip, open, side, align, 6)

  const show = useCallback((wait: number) => {
    window.clearTimeout(timer.current)
    timer.current = window.setTimeout(() => setOpen(true), wait)
  }, [])
  const hide = useCallback((wait = 80) => {
    window.clearTimeout(timer.current)
    timer.current = window.setTimeout(() => setOpen(false), wait)
  }, [])

  useEffect(() => () => window.clearTimeout(timer.current), [])
  useEffect(() => {
    if (!open) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false)
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [open])

  if (disabled || !isValidElement(children)) return children
  const child = children as ReactElement<{ 'aria-describedby'?: string }>
  const describedBy = labelOnly ? child.props['aria-describedby'] : cx(child.props['aria-describedby'], id) || undefined

  return (
    <span
      ref={anchorRef}
      className="ui-tooltip-anchor"
      onPointerEnter={e => e.pointerType === 'mouse' && show(delay)}
      onPointerLeave={() => hide()}
      onFocus={e => (e.target as HTMLElement).matches(':focus-visible') && show(0)}
      onBlur={() => hide(0)}
    >
      {cloneElement(child, { 'aria-describedby': describedBy })}
      {open &&
        createPortal(
          <div
            ref={setTip}
            id={id}
            role="tooltip"
            className={cx('ui-tooltip', className)}
            data-side={placed}
            style={style}
            onPointerEnter={() => window.clearTimeout(timer.current)}
            onPointerLeave={() => hide()}
          >
            {content}
          </div>,
          portalTarget(anchorRef.current),
        )}
      {!open && !labelOnly && (
        <span id={id} hidden>
          {content}
        </span>
      )}
    </span>
  )
}
