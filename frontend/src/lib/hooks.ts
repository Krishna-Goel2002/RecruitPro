import { useEffect, useRef, useState } from 'react'

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
    document.title = title ? `${title} · RecruitPro` : 'RecruitPro Candidate Portal'
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
