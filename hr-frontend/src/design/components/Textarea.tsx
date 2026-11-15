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

  const resize = useCallback(() => {
    const el = local.current
    if (!el || !autoGrow) return
    const cs = getComputedStyle(el)
    const line = parseFloat(cs.lineHeight) || 20
    const chrome = parseFloat(cs.paddingTop) + parseFloat(cs.paddingBottom) + parseFloat(cs.borderTopWidth) + parseFloat(cs.borderBottomWidth)
    el.style.height = 'auto'
    const max = line * maxRows + chrome
    const min = line * rows + chrome
    el.style.height = `${Math.min(max, Math.max(min, el.scrollHeight + parseFloat(cs.borderTopWidth) + parseFloat(cs.borderBottomWidth)))}px`
    el.style.overflowY = el.scrollHeight > max ? 'auto' : 'hidden'
  }, [autoGrow, maxRows, rows])

  useLayoutEffect(() => {
    resize()
    if (rest.value !== undefined) setCount(String(rest.value).length)
  }, [rest.value, resize])

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
