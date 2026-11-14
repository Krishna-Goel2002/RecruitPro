import { KeyboardEvent, ReactNode, useId, useRef } from 'react'

export type TabItem<K extends string> = { key: K; label: string; badge?: string | number }

/**
 * WAI-ARIA tabs with roving focus (arrow keys, Home, End). Only the active panel is rendered; it is labelled by its
 * tab. `trailing` renders extra controls (e.g. an export button) at the end of the tab row.
 */
export function Tabs<K extends string>({ items, active, onChange, label, trailing, children }: {
  items: TabItem<K>[]
  active: K
  onChange: (key: K) => void
  label: string
  trailing?: ReactNode
  children: ReactNode
}) {
  const baseId = useId()
  const refs = useRef<Record<string, HTMLButtonElement | null>>({})

  function onKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    const index = items.findIndex(item => item.key === active)
    let next = index
    if (event.key === 'ArrowRight') next = (index + 1) % items.length
    else if (event.key === 'ArrowLeft') next = (index - 1 + items.length) % items.length
    else if (event.key === 'Home') next = 0
    else if (event.key === 'End') next = items.length - 1
    else return
    event.preventDefault()
    const key = items[next].key
    onChange(key)
    refs.current[key]?.focus()
  }

  return (
    <>
      <div className="tab-row">
        <div className="tab-list" role="tablist" aria-label={label} onKeyDown={onKeyDown}>
          {items.map(item => (
            <button
              key={item.key}
              ref={element => { refs.current[item.key] = element }}
              type="button"
              role="tab"
              id={`${baseId}-tab-${item.key}`}
              aria-selected={item.key === active}
              aria-controls={`${baseId}-panel`}
              tabIndex={item.key === active ? 0 : -1}
              className={`tab${item.key === active ? ' active' : ''}`}
              onClick={() => onChange(item.key)}
            >
              {item.label}
              {item.badge != null && <span className="tab-badge">{item.badge}</span>}
            </button>
          ))}
        </div>
        {trailing && <div className="tab-trailing">{trailing}</div>}
      </div>
      <div role="tabpanel" id={`${baseId}-panel`} aria-labelledby={`${baseId}-tab-${active}`} className="tab-panel">
        {children}
      </div>
    </>
  )
}
