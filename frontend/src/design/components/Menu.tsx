import { useCallback, useEffect, useId, useRef, useState, type KeyboardEvent, type ReactElement, type ReactNode } from 'react'
import { createPortal } from 'react-dom'
import { useNavigate } from 'react-router-dom'
import { Icon, type IconComponent } from '../icons'
import { cx } from './cx'
import { useOutside } from './hooks'
import { portalTarget, useFloating, type Align } from './position'

export interface MenuItem {
  label: string
  icon?: IconComponent
  onSelect?: () => void
  /** Router path to navigate to. */
  to?: string
  danger?: boolean
  disabled?: boolean
  /** Draw a hairline above this item. */
  separated?: boolean
}

export interface MenuTriggerProps {
  ref: (el: HTMLButtonElement | null) => void
  'aria-haspopup': 'menu'
  'aria-expanded': boolean
  'aria-controls': string
  onClick: () => void
  onKeyDown: (e: KeyboardEvent<HTMLButtonElement>) => void
}

export interface MenuProps {
  /** Render the trigger button and spread the props onto it. */
  trigger: (props: MenuTriggerProps) => ReactElement
  items: MenuItem[]
  align?: Align
  label?: string
}

/**
 * Menu (§9.18): role="menu" in a floating layer (radius 6, padding 4, 32 px items). ↑/↓/Home/End move, Enter
 * selects, Escape or Tab closes and returns focus to the trigger. For the "⋯" overflow of page and row actions.
 */
export function Menu({ trigger, items, align = 'end', label }: MenuProps) {
  const id = useId()
  const navigate = useNavigate()
  const [open, setOpen] = useState(false)
  const [btn, setBtn] = useState<HTMLButtonElement | null>(null)
  const [menu, setMenu] = useState<HTMLDivElement | null>(null)
  const focusFirst = useRef<'first' | 'last'>('first')
  const { style } = useFloating(btn, menu, open, 'bottom', align, 4)

  const close = useCallback((refocus = true) => {
    setOpen(false)
    if (refocus) btn?.focus()
  }, [btn])
  useOutside([btn, menu], open, () => setOpen(false))

  useEffect(() => {
    if (!open || !menu) return
    const list = menu.querySelectorAll<HTMLElement>('[role="menuitem"]:not([aria-disabled="true"])')
    ;(focusFirst.current === 'first' ? list[0] : list[list.length - 1])?.focus()
  }, [open, menu])

  const onMenuKey = (e: KeyboardEvent<HTMLDivElement>) => {
    const list = Array.from(menu?.querySelectorAll<HTMLElement>('[role="menuitem"]:not([aria-disabled="true"])') ?? [])
    const i = list.indexOf(document.activeElement as HTMLElement)
    if (e.key === 'ArrowDown') list[(i + 1) % list.length]?.focus()
    else if (e.key === 'ArrowUp') list[(i - 1 + list.length) % list.length]?.focus()
    else if (e.key === 'Home') list[0]?.focus()
    else if (e.key === 'End') list[list.length - 1]?.focus()
    else if (e.key === 'Escape') close()
    else if (e.key === 'Tab') close(false)
    else return
    if (e.key !== 'Tab') e.preventDefault()
  }

  const select = (item: MenuItem) => {
    if (item.disabled) return
    close()
    if (item.to) navigate(item.to)
    item.onSelect?.()
  }

  return (
    <>
      {trigger({
        ref: setBtn,
        'aria-haspopup': 'menu',
        'aria-expanded': open,
        'aria-controls': `${id}-menu`,
        onClick: () => {
          focusFirst.current = 'first'
          setOpen(o => !o)
        },
        onKeyDown: e => {
          if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
            e.preventDefault()
            focusFirst.current = e.key === 'ArrowDown' ? 'first' : 'last'
            setOpen(true)
          }
        },
      })}
      {open &&
        createPortal(
          <div ref={setMenu} id={`${id}-menu`} role="menu" aria-label={label} className="ui-menu" style={style} onKeyDown={onMenuKey}>
            {items.map((item, i) => (
              <MenuRow key={`${item.label}-${i}`} item={item} onSelect={() => select(item)} />
            ))}
          </div>,
          portalTarget(btn),
        )}
    </>
  )
}

function MenuRow({ item, onSelect }: { item: MenuItem; onSelect: () => void }) {
  return (
    <>
      {item.separated && <div role="separator" className="ui-menu-sep" />}
      <button
        type="button"
        role="menuitem"
        tabIndex={-1}
        className={cx('ui-menu-item')}
        data-danger={item.danger || undefined}
        aria-disabled={item.disabled || undefined}
        onClick={onSelect}
      >
        {item.icon && <Icon icon={item.icon} size={16} />}
        <span>{item.label}</span>
      </button>
    </>
  )
}

/** A non-menu floating panel anchored to a disclosure button (account menu, filters, "How the ladder works"). */
export function Popover({
  anchor,
  open,
  onClose,
  children,
  align = 'end',
  label,
  className,
}: {
  anchor: HTMLElement | null
  open: boolean
  onClose: () => void
  children: ReactNode
  align?: Align
  label: string
  className?: string
}) {
  const [panel, setPanel] = useState<HTMLDivElement | null>(null)
  const { style } = useFloating(anchor, panel, open, 'bottom', align, 6)
  useOutside([anchor, panel], open, onClose)
  useEffect(() => {
    if (!open) return
    const onKey = (e: globalThis.KeyboardEvent) => {
      if (e.key === 'Escape') {
        onClose()
        anchor?.focus()
      }
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [open, onClose, anchor])
  if (!open) return null
  return createPortal(
    <div ref={setPanel} role="dialog" aria-label={label} className={cx('ui-popover', className)} style={style}>
      {children}
    </div>,
    portalTarget(anchor),
  )
}
