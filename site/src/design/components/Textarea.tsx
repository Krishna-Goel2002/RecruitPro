import { forwardRef, useCallback, useLayoutEffect, useRef, useState, type TextareaHTMLAttributes } from 'react'
import { formatNumber } from '../lib/format'
import { cx } from './cx'
import { useFieldControl } from './Field'
import { mergeRefs } from './hooks'

export interface TextareaProps extends TextareaHTMLAttributes<HTMLTextAreaElement> {
  /** Grows with the content from `rows` up to `maxRows`. Default true. */
  autoGrow?: boolean
  maxRows?: number
  /** Shows "120 / 1,000" under the field (needs maxLength). */
  showCount?: boolean
}

/** Multi-line input (§9.3): auto-grows, optional character counter. */
export const Textarea = forwardRef<HTMLTextAreaElement, TextareaProps>(function Textarea(
  { autoGrow = true, rows = 4, maxRows = 12, showCount, className, onChange, ...rest },
  ref,
) {
  const local = useRef<HTMLTextAreaElement>(null)
  const field = useFieldControl(rest)
  const [count, setCount] = useState(() => String(rest.value ?? rest.defaultValue ?? '').length)

  // Height the user chose by dragging the resize handle; auto-grow then stops overriding it.
  const manual = useRef(false)
  const lastSet = useRef<string | null>(null)

  const resize = useCallback(() => {
    const el = local.current
    if (!el || !autoGrow || manual.current) return
    // Hidden (closed dialog or drawer, inactive tab): nothing to measure yet; the ResizeObserver below re-runs this
    // when the field becomes visible, so long content is never left clipped at the minimum height.
    if (el.offsetParent === null && el.getClientRects().length === 0) return
    const cs = getComputedStyle(el)
    const line = parseFloat(cs.lineHeight) || 20
    const borders = parseFloat(cs.borderTopWidth) + parseFloat(cs.borderBottomWidth)
    const chrome = parseFloat(cs.paddingTop) + parseFloat(cs.paddingBottom) + borders
    el.style.height = 'auto'
    const max = line * maxRows + chrome
    const min = line * rows + chrome
    const next = `${Math.min(max, Math.max(min, el.scrollHeight + borders))}px`
    el.style.height = next
    lastSet.current = next
  }, [autoGrow, maxRows, rows])

  useLayoutEffect(() => {
    resize()
    if (rest.value !== undefined) setCount(String(rest.value).length)
  }, [rest.value, resize])

  // Re-measure when the field becomes visible or its width changes (wrapping changes the height).
  useLayoutEffect(() => {
    const el = local.current
    if (!el || !autoGrow || typeof ResizeObserver === 'undefined') return
    let width = el.clientWidth
    const ro = new ResizeObserver(() => {
      if (el.clientWidth === width) return
      width = el.clientWidth
      resize()
    })
    ro.observe(el)
    return () => ro.disconnect()
  }, [autoGrow, resize])

  const countId = showCount && rest.maxLength ? `${field.id}-count` : undefined
  return (
    <div className={cx('ui-textarea', className)}>
      <textarea
        ref={mergeRefs(ref, local)}
        rows={rows}
        className="ui-input"
        data-autogrow={autoGrow || undefined}
        onChange={e => {
          setCount(e.target.value.length)
          resize()
          onChange?.(e)
        }}
        onPointerUp={e => {
          // The resize handle sets an inline height; keep the user's choice from then on.
          const h = e.currentTarget.style.height
          if (lastSet.current && h && h !== lastSet.current) manual.current = true
        }}
        {...rest}
        id={field.id}
        aria-describedby={cx(field['aria-describedby'], countId) || undefined}
        aria-invalid={field['aria-invalid']}
        required={rest.required ?? field.required}
      />
      {countId && (
        <span id={countId} className="ui-textarea-count numeric" data-over={count > (rest.maxLength ?? Infinity) || undefined}>
          {formatNumber(count)} / {formatNumber(rest.maxLength!)}
        </span>
      )}
    </div>
  )
})
