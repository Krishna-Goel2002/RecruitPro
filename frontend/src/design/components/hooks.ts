import { useCallback, useEffect, useState, useSyncExternalStore, type Ref, type RefCallback } from 'react'

/** Live media-query match (SSR-safe default false). */
export function useMediaQuery(query: string): boolean {
  const subscribe = useCallback(
    (fn: () => void) => {
      const m = window.matchMedia(query)
      m.addEventListener('change', fn)
      return () => m.removeEventListener('change', fn)
    },
    [query],
  )
  return useSyncExternalStore(subscribe, () => window.matchMedia(query).matches, () => false)
}

/** State persisted per viewer in localStorage; every access is wrapped (private windows, blocked storage). */
export function useStoredState<T extends string>(key: string, fallback: T, allowed?: readonly T[]): [T, (v: T) => void] {
  const [value, setValue] = useState<T>(() => {
    try {
      const v = localStorage.getItem(key) as T | null
      if (v !== null && (!allowed || allowed.includes(v))) return v
    } catch {
      /* ignore */
    }
    return fallback
  })
  const set = useCallback(
    (v: T) => {
      setValue(v)
      try {
        localStorage.setItem(key, v)
      } catch {
        /* ignore */
      }
    },
    [key],
  )
  return [value, set]
}

/** Merge several refs (forwarded + local) into one callback ref. */
export function mergeRefs<T>(...refs: (Ref<T> | undefined)[]): RefCallback<T> {
  return (node: T) => {
    for (const r of refs) {
      if (typeof r === 'function') r(node)
      else if (r) (r as { current: T | null }).current = node
    }
  }
}

/** Calls `fn` on pointerdown outside every element in `els` while `active`. */
export function useOutside(els: (HTMLElement | null)[], active: boolean, fn: () => void) {
  useEffect(() => {
    if (!active) return
    const onDown = (e: PointerEvent) => {
      const t = e.target as Node
      if (els.every(el => !el || !el.contains(t))) fn()
    }
    document.addEventListener('pointerdown', onDown, true)
    return () => document.removeEventListener('pointerdown', onDown, true)
  }, [els, active, fn])
}
