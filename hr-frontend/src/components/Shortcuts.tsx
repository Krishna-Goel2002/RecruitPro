import { useEffect, useRef, type ReactNode } from 'react'
import { Dialog } from '../design/components'
import './hra.css'

/** A key cap. Decorative text (the action is always named next to it). */
export function Kbd({ children }: { children: ReactNode }) {
  return <kbd className="hra-kbd">{children}</kbd>
}

export interface ShortcutGroup {
  title: string
  items: { keys: string[]; label: string }[]
}

/** The "?" sheet: every shortcut on the current screen, grouped. */
export function ShortcutSheet({ open, onClose, groups }: { open: boolean; onClose: () => void; groups: ShortcutGroup[] }) {
  return (
    <Dialog open={open} onClose={onClose} title="Keyboard shortcuts" size="sm" description="Shortcuts work when no text field has focus.">
      <div className="hra-shortcut-sheet">
        {groups.map(group => (
          <section key={group.title}>
            <h3 className="t-label">{group.title}</h3>
            <dl>
              {group.items.map(item => (
                <div key={item.label} className="hra-shortcut-row">
                  <dt>{item.label}</dt>
                  <dd>
                    {item.keys.map((key, i) => (
                      <span key={key}>
                        {i > 0 && <span className="hra-shortcut-or">or</span>}
                        <Kbd>{key}</Kbd>
                      </span>
                    ))}
                  </dd>
                </div>
              ))}
            </dl>
          </section>
        ))}
      </div>
    </Dialog>
  )
}

/** True when a key press belongs to a text field, a menu or an open overlay rather than to page shortcuts. */
export function isTypingTarget(target: EventTarget | null): boolean {
  const el = target as HTMLElement | null
  if (!el || !el.closest) return false
  if (el.isContentEditable) return true
  if (el.closest('input, textarea, select, [role="menu"], [role="listbox"], [contenteditable="true"]')) {
    const input = el.closest('input') as HTMLInputElement | null
    // Checkboxes and radios do not swallow letters.
    return !(input && (input.type === 'checkbox' || input.type === 'radio'))
  }
  return false
}

/** True while any modal <dialog> is open (page shortcuts must not fire behind it). */
export function modalOpen(): boolean {
  return document.querySelector('dialog[open]') != null
}

/**
 * Page-level single-key shortcuts. Ignored while typing, with modifiers (except Shift for "?"), or while a dialog is
 * open unless `inDialog` is set for that key.
 */
export function useShortcuts(map: Record<string, (event: KeyboardEvent) => void>, enabled = true, inDialog: string[] = []) {
  const ref = useRef(map)
  ref.current = map
  useEffect(() => {
    if (!enabled) return
    const onKey = (event: KeyboardEvent) => {
      if (event.defaultPrevented || event.metaKey || event.ctrlKey || event.altKey) return
      if (isTypingTarget(event.target)) return
      const key = event.key
      // Space and Enter on a button or link keep their native meaning.
      if ((key === ' ' || key === 'Enter') && (event.target as HTMLElement | null)?.closest?.('button, a[href], summary, [role="button"], [role="tab"]')) return
      const handler = ref.current[key]
      if (!handler) return
      if (modalOpen() && !inDialog.includes(key)) return
      // The key belongs to the shortcut: it must not also type into a field that the action focuses (a dialog).
      if (key.length === 1) event.preventDefault()
      handler(event)
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [enabled, inDialog.join('|')])
}
