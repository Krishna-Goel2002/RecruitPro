import { useId, useLayoutEffect, useRef, useState, type ReactNode } from 'react'
import { Icon, type IconComponent } from '../icons'
import { cx } from './cx'

export interface SegmentOption<V extends string> {
  value: V
  label: ReactNode
  icon?: IconComponent
  /** Hide the text visually (icon-only segment); the label stays the accessible name. */
  iconOnly?: boolean
  disabled?: boolean
}

export interface SegmentedControlProps<V extends string> {
  /** Accessible name of the group. */
  label: string
  options: SegmentOption<V>[]
  value: V | null
  onChange: (value: V) => void
  size?: 'sm' | 'md'
  name?: string
  className?: string
  /** Stretch segments to fill the width. */
  block?: boolean
}

/**
 * Segmented control (§9.7): a radiogroup of native radios (arrow keys work natively) on a sunken track; the
 * selected segment is a raised thumb that slides between options (M11-style, 200 ms standard).
 */
export function SegmentedControl<V extends string>({ label, options, value, onChange, size = 'md', name, className, block }: SegmentedControlProps<V>) {
  const auto = useId()
  const group = name ?? `seg${auto}`
  const ref = useRef<HTMLDivElement>(null)
  const [thumb, setThumb] = useState<{ x: number; w: number } | null>(null)

  useLayoutEffect(() => {
    const root = ref.current
    if (!root) return
    const measure = () => {
      const sel = root.querySelector<HTMLElement>('[data-checked]')
      setThumb(sel ? { x: sel.offsetLeft, w: sel.offsetWidth } : null)
    }
    measure()
    const ro = typeof ResizeObserver !== 'undefined' ? new ResizeObserver(measure) : null
    ro?.observe(root)
    return () => ro?.disconnect()
  }, [value, options.length])

  return (
    <div ref={ref} role="radiogroup" aria-label={label} className={cx('ui-seg', className)} data-size={size} data-block={block || undefined}>
      {thumb && <span className="ui-seg-thumb" aria-hidden="true" style={{ transform: `translateX(${thumb.x}px)`, width: thumb.w }} />}
      {options.map(o => {
        const checked = o.value === value
        return (
          <label key={o.value} className="ui-seg-item" data-checked={checked || undefined} data-disabled={o.disabled || undefined}>
            <input
              type="radio"
              className="ui-seg-input"
              name={group}
              value={o.value}
              checked={checked}
              disabled={o.disabled}
              onChange={() => onChange(o.value)}
            />
            {o.icon && <Icon icon={o.icon} size={16} />}
            <span className={o.iconOnly ? 'sr-only' : undefined}>{o.label}</span>
          </label>
        )
      })}
    </div>
  )
}
