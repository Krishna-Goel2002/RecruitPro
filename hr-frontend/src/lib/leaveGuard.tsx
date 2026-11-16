import { useEffect, useRef, useState, type ReactNode, type RefObject } from 'react'
import { ActionDialog } from '../components/ActionDialog'

/**
 * "Leave without saving?" for forms and editors (completeness sweep). While `when` is true:
 *
 * - closing or reloading the tab makes the browser ask (beforeunload);
 * - a click on an in-app link (`a[href]`, same origin, another page) or on a tab (`[role="tab"]`) outside `scope` is
 *   held and a confirmation opens; "Leave without saving" replays the click, "Keep editing" stays.
 *
 * The app uses a plain BrowserRouter (no data router, so no `useBlocker`); listening in the capture phase on the
 * document runs before React's own handlers, so a held click never reaches the router. Programmatic navigation and
 * the browser's Back button are not held (beforeunload covers leaving the app).
 */
export function useLeaveGuard(
  when: boolean,
  options: { scope?: RefObject<HTMLElement>; title?: string; body?: ReactNode } = {},
): ReactNode {
  const [held, setHeld] = useState<HTMLElement | null>(null)
  const bypass = useRef(false)
  const scope = options.scope

  useEffect(() => {
    if (!when) return
    const onUnload = (event: BeforeUnloadEvent) => {
      event.preventDefault()
      event.returnValue = ''
    }
    const onClick = (event: MouseEvent) => {
      if (bypass.current || event.defaultPrevented || event.button !== 0) return
      if (event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return
      const origin = event.target instanceof Element ? event.target : null
      const element = origin?.closest<HTMLElement>('a[href], [role="tab"]')
      if (!element) return
      if (scope?.current && scope.current.contains(element)) return
      // Clicks inside an open dialog (including this guard's own) are part of the current task.
      if (element.closest('dialog[open]')) return
      if (element.getAttribute('role') === 'tab') {
        if (element.getAttribute('aria-selected') === 'true') return
      } else {
        const anchor = element as HTMLAnchorElement
        if ((anchor.target && anchor.target !== '_self') || anchor.hasAttribute('download')) return
        const url = new URL(anchor.href, window.location.href)
        if (url.origin !== window.location.origin) return
        if (url.pathname === window.location.pathname && url.search === window.location.search) return
      }
      event.preventDefault()
      event.stopPropagation()
      setHeld(element)
    }
    window.addEventListener('beforeunload', onUnload)
    document.addEventListener('click', onClick, true)
    return () => {
      window.removeEventListener('beforeunload', onUnload)
      document.removeEventListener('click', onClick, true)
    }
  }, [when, scope])

  return (
    <ActionDialog
      open={held != null}
      size="sm"
      tone="danger"
      title={options.title ?? 'Leave without saving?'}
      confirmLabel="Leave without saving"
      cancelLabel="Keep editing"
      onCancel={() => setHeld(null)}
      onConfirm={() => {
        const element = held
        setHeld(null)
        if (!element || !element.isConnected) return
        bypass.current = true
        try {
          element.click()
        } finally {
          bypass.current = false
        }
      }}
    >
      {options.body ?? <p>Your changes on this page have not been saved yet and will be lost.</p>}
    </ActionDialog>
  )
}

/** "Discard changes?" for a drawer or inline editor whose Cancel/Close would throw edits away. */
export function useDiscardGuard(dirty: boolean, onDiscard: () => void, what = 'changes') {
  const [asking, setAsking] = useState(false)
  const request = () => {
    if (dirty) setAsking(true)
    else onDiscard()
  }
  const dialog = (
    <ActionDialog
      open={asking}
      size="sm"
      tone="danger"
      title={`Discard your ${what}?`}
      confirmLabel="Discard"
      cancelLabel="Keep editing"
      onCancel={() => setAsking(false)}
      onConfirm={() => {
        setAsking(false)
        onDiscard()
      }}
    >
      <p>What you changed here has not been saved.</p>
    </ActionDialog>
  )
  return { request, dialog }
}
