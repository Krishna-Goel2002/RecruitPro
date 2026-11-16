import { useId, useRef, useState, type KeyboardEvent, type ReactNode } from 'react'
import { ChevronDown, Icon } from '../design/icons'

export interface FaqItem {
  q: string
  a: ReactNode
}

/**
 * FAQ accordion (WAI-ARIA accordion pattern): each question is a button in a heading with aria-expanded and
 * aria-controls; its answer is a labelled region. Enter/Space toggle (native button); ↑/↓ move between questions
 * (wrapping), Home/End jump to the first/last. Several answers may be open at once. The panel height animates with
 * a grid-rows transition; a closed panel is visibility:hidden, so it is out of the tab order and the a11y tree.
 */
export function Faq({ items, defaultOpen = [] }: { items: FaqItem[]; defaultOpen?: number[] }) {
  const base = useId().replace(/:/g, '')
  const [open, setOpen] = useState<ReadonlySet<number>>(() => new Set(defaultOpen))
  const buttons = useRef<(HTMLButtonElement | null)[]>([])

  const toggle = (i: number) =>
    setOpen(prev => {
      const next = new Set(prev)
      if (next.has(i)) next.delete(i)
      else next.add(i)
      return next
    })

  const onKeyDown = (e: KeyboardEvent<HTMLButtonElement>, i: number) => {
    const last = items.length - 1
    const target =
      e.key === 'ArrowDown' ? (i === last ? 0 : i + 1)
      : e.key === 'ArrowUp' ? (i === 0 ? last : i - 1)
      : e.key === 'Home' ? 0
      : e.key === 'End' ? last
      : null
    if (target === null) return
    e.preventDefault()
    buttons.current[target]?.focus()
  }

  return (
    <div className="faq">
      {items.map((item, i) => {
        const isOpen = open.has(i)
        const qid = `${base}-q-${i}`
        const aid = `${base}-a-${i}`
        return (
          <div key={item.q} className="faq-item" data-open={isOpen || undefined}>
            <h3 className="faq-q">
              <button
                ref={el => {
                  buttons.current[i] = el
                }}
                type="button"
                id={qid}
                className="faq-btn"
                aria-expanded={isOpen}
                aria-controls={aid}
                onClick={() => toggle(i)}
                onKeyDown={e => onKeyDown(e, i)}
              >
                <span>{item.q}</span>
                <Icon icon={ChevronDown} size={20} className="faq-chevron" />
              </button>
            </h3>
            <div id={aid} role="region" aria-labelledby={qid} className="faq-a" data-open={isOpen || undefined}>
              <div className="faq-a-inner">
                <div className="faq-a-body">{item.a}</div>
              </div>
            </div>
          </div>
        )
      })}
    </div>
  )
}
