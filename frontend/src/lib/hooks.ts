import type React from 'react'
import { useEffect, useRef, useState } from 'react'
import { useLocation } from 'react-router-dom'
import brand from '../design/brand/active'
import { stagger } from '../design/motion'

/** Returns `value` after it has stopped changing for `delayMs`. */
export function useDebouncedValue<T>(value: T, delayMs: number): T {
  const [debounced, setDebounced] = useState(value)
  useEffect(() => {
    const timer = window.setTimeout(() => setDebounced(value), delayMs)
    return () => window.clearTimeout(timer)
  }, [value, delayMs])
  return debounced
}

/** Sets `document.title` while the page is mounted. */
export function useDocumentTitle(title: string): void {
  useEffect(() => {
    const previous = document.title
    document.title = title ? `${title} · ${brand.name}` : brand.name
    return () => {
      document.title = previous
    }
  }, [title])
}

/** Current time, re-rendered every `intervalMs` while `active`. */
export function useNow(intervalMs: number, active = true): number {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    if (!active) return
    setNow(Date.now())
    const timer = window.setInterval(() => setNow(Date.now()), intervalMs)
    return () => window.clearInterval(timer)
  }, [intervalMs, active])
  return now
}

/** Warns before leaving the page (reload/close) while `when` is true. */
export function useBeforeUnload(when: boolean): void {
  const whenRef = useRef(when)
  whenRef.current = when
  useEffect(() => {
    const handler = (event: BeforeUnloadEvent) => {
      if (!whenRef.current) return
      event.preventDefault()
      event.returnValue = ''
    }
    window.addEventListener('beforeunload', handler)
    return () => window.removeEventListener('beforeunload', handler)
  }, [])
}

/**
 * Scrolls to the element named by the URL hash (`/verification#references`) once the page content is `ready`.
 * React Router does not do this, and the shell scrolls to the top after each navigation, so links with a hash used to
 * land at the top of the page. Runs once per hash; the target's heading (or the target) takes focus.
 */
export function useScrollToHash(ready: boolean): void {
  const { hash } = useLocation()
  const done = useRef<string | null>(null)
  useEffect(() => {
    if (!ready || !hash || done.current === hash) return
    const id = decodeURIComponent(hash.slice(1))
    // After the shell's own scroll-to-top (a 0 ms timer on navigation).
    const timer = window.setTimeout(() => {
      const target = document.getElementById(id)
      if (!target) return
      done.current = hash
      target.scrollIntoView?.({ block: 'start' })
      const heading = target.querySelector<HTMLElement>('h2, h3') ?? target
      if (!heading.hasAttribute('tabindex')) heading.setAttribute('tabindex', '-1')
      heading.focus({ preventScroll: true })
    }, 50)
    return () => window.clearTimeout(timer)
  }, [ready, hash])
}

/**
 * List entrance on FIRST load only (M2): staggers the children of `ref` the first time `ready` is true, never again
 * on refetch or filter changes. Uses the design-system `stagger` (a no-op under reduced motion).
 */
export function useEnterOnce<T extends HTMLElement>(ref: React.RefObject<T>, ready: boolean): void {
  const done = useRef(false)
  useEffect(() => {
    if (done.current || !ready || !ref.current) return
    done.current = true
    stagger(Array.from(ref.current.children) as HTMLElement[])
  }, [ready, ref])
}
