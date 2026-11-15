import { useCallback, useLayoutEffect, useRef, useState, type HTMLAttributes, type KeyboardEvent, type ReactNode } from 'react'
import { formatNumber } from '../lib/format'
import { cx } from './cx'

export interface TabItem<V extends string = string> {
  value: V
  label: ReactNode
  /** Neutral count badge after the label. */
  count?: number
  disabled?: boolean
}

export interface TabsProps<V extends string> {
  /** Accessible name of the tab list. */
  label: string
  /** Prefix for tab/panel ids; use the same value on <TabPanel idBase>. */
  idBase: string
  items: TabItem<V>[]
  value: V
  onChange: (value: V) => void
  className?: string
}

export const tabId = (base: string, value: string) => `${base}-tab-${value}`
export const panelId = (base: string, value: string) => `${base}-panel-${value}`

/**
 * Underline tabs (§9.8, M11): 40 px, the 2 px accent indicator slides between tabs with a transform. WAI-ARIA
 * automatic activation: ←/→ move and select, Home/End jump. Overflow scrolls horizontally with edge fades, and the
 * active tab is scrolled into view.
 */
export function Tabs<V extends string>({ label, idBase, items, value, onChange, className }: TabsProps<V>) {
  const listRef = useRef<HTMLDivElement>(null)
  const scrollRef = useRef<HTMLDivElement>(null)
  const [indicator, setIndicator] = useState<{ x: number; w: number } | null>(null)
  const [edges, setEdges] = useState({ start: false, end: false })

  const measureEdges = useCallback(() => {
    const s = scrollRef.current
    if (!s) return
    setEdges({ start: s.scrollLeft > 1, end: s.scrollLeft + s.clientWidth < s.scrollWidth - 1 })
  }, [])

  useLayoutEffect(() => {
    const list = listRef.current
    if (!list) return
    const measure = () => {
      const active = list.querySelector<HTMLElement>('[aria-selected="true"]')
      setIndicator(active ? { x: active.offsetLeft, w: active.offsetWidth } : null)
      measureEdges()
    }
    measure()
    const active = list.querySelector<HTMLElement>('[aria-selected="true"]')
    const s = scrollRef.current
    if (active && s) {
      const l = active.offsetLeft
      const r = l + active.offsetWidth
      if (l < s.scrollLeft + 24) s.scrollLeft = Math.max(0, l - 24)
      else if (r > s.scrollLeft + s.clientWidth - 24) s.scrollLeft = r - s.clientWidth + 24
    }
    const ro = typeof ResizeObserver !== 'undefined' ? new ResizeObserver(measure) : null
    ro?.observe(list)
    return () => ro?.disconnect()
  }, [value, items, measureEdges])

  const enabled = items.filter(i => !i.disabled)
  const onKeyDown = (e: KeyboardEvent<HTMLButtonElement>) => {
    const i = enabled.findIndex(t => t.value === value)
    let next: TabItem<V> | undefined
    if (e.key === 'ArrowRight') next = enabled[(i + 1) % enabled.length]
    else if (e.key === 'ArrowLeft') next = enabled[(i - 1 + enabled.length) % enabled.length]
    else if (e.key === 'Home') next = enabled[0]
    else if (e.key === 'End') next = enabled[enabled.length - 1]
    if (!next) return
    e.preventDefault()
    onChange(next.value)
    requestAnimationFrame(() => document.getElementById(tabId(idBase, next!.value))?.focus())
  }

  return (
    <div className={cx('ui-tabs', className)} data-fade-start={edges.start || undefined} data-fade-end={edges.end || undefined}>
      <div className="ui-tabs-scroll" ref={scrollRef} onScroll={measureEdges}>
        <div ref={listRef} role="tablist" aria-label={label} className="ui-tabs-list">
          {items.map(t => {
            const selected = t.value === value
            return (
              <button
                key={t.value}
                id={tabId(idBase, t.value)}
                type="button"
                role="tab"
                className="ui-tab"
                aria-selected={selected}
                aria-controls={panelId(idBase, t.value)}
                tabIndex={selected ? 0 : -1}
                disabled={t.disabled}
                onClick={() => onChange(t.value)}
                onKeyDown={onKeyDown}
              >
                <span>{t.label}</span>
                {t.count != null && <span className="ui-tab-count numeric">{formatNumber(t.count)}</span>}
              </button>
            )
          })}
          {indicator && (
            <span className="ui-tabs-indicator" aria-hidden="true" style={{ transform: `translateX(${indicator.x}px) scaleX(${indicator.w})` }} />
          )}
        </div>
      </div>
    </div>
  )
}

export interface TabPanelProps extends HTMLAttributes<HTMLDivElement> {
  idBase: string
  value: string
  /** The currently selected tab value. Panels for other tabs render nothing. */
  active: string
}

/** The panel for one tab; crossfades in over 140 ms when it becomes active. */
export function TabPanel({ idBase, value, active, className, children, ...rest }: TabPanelProps) {
  if (value !== active) return null
  return (
    <div
      role="tabpanel"
      id={panelId(idBase, value)}
      aria-labelledby={tabId(idBase, value)}
      tabIndex={0}
      className={cx('ui-tabpanel', className)}
      {...rest}
    >
      {children}
    </div>
  )
}
