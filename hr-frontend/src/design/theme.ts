/*
 * Theme preference (DESIGN_SYSTEM §10): 'system' | 'light' | 'dark', stored per viewer in
 * localStorage['theme-preference'] (every access in try/catch), applied as data-theme + color-scheme on <html>.
 * public/theme-init.js applies the same logic before first paint; this module takes over after hydration.
 */
import { useSyncExternalStore } from 'react'
import { withViewTransition } from './motion'

export type ThemePreference = 'system' | 'light' | 'dark'
export type ResolvedTheme = 'light' | 'dark'

export const THEME_STORAGE_KEY = 'theme-preference'

const listeners = new Set<() => void>()
const media = typeof window !== 'undefined' && window.matchMedia ? window.matchMedia('(prefers-color-scheme: dark)') : null

function read(): ThemePreference {
  try {
    const v = localStorage.getItem(THEME_STORAGE_KEY)
    return v === 'light' || v === 'dark' ? v : 'system'
  } catch {
    return 'system'
  }
}

let preference: ThemePreference = read()

export function getPreference(): ThemePreference {
  return preference
}

export function resolveTheme(p: ThemePreference = preference): ResolvedTheme {
  if (p === 'system') return media?.matches ? 'dark' : 'light'
  return p
}

function apply() {
  const theme = resolveTheme()
  const root = document.documentElement
  root.setAttribute('data-theme', theme)
  root.style.colorScheme = theme
  // Keep the browser chrome in step with the chosen theme (the metas carry media queries for the OS theme).
  const canvas = getComputedStyle(root).getPropertyValue('--bg-canvas').trim()
  if (canvas) document.querySelectorAll<HTMLMetaElement>('meta[name="theme-color"]').forEach(m => (m.content = canvas))
}

function emit() {
  listeners.forEach(fn => fn())
}

/**
 * Persist and apply a preference. When `origin` (the toggle) is given and the browser supports View Transitions,
 * the new theme is revealed by a circle growing from the toggle's centre (M14); otherwise a 200 ms colour crossfade.
 */
export function setPreference(next: ThemePreference, origin?: HTMLElement | null) {
  const before = resolveTheme()
  preference = next
  try {
    if (next === 'system') localStorage.removeItem(THEME_STORAGE_KEY)
    else localStorage.setItem(THEME_STORAGE_KEY, next)
  } catch {
    /* storage blocked: the choice lasts for this page load */
  }
  if (resolveTheme() === before) {
    apply()
    emit()
    return
  }
  let point: { x: number; y: number } | undefined
  if (origin) {
    const r = origin.getBoundingClientRect()
    point = { x: r.left + r.width / 2, y: r.top + r.height / 2 }
  }
  withViewTransition(() => {
    apply()
    emit()
  }, point)
}

export function subscribe(fn: () => void): () => void {
  listeners.add(fn)
  return () => listeners.delete(fn)
}

media?.addEventListener('change', () => {
  if (preference === 'system') {
    apply()
    emit()
  }
})

// Another tab changed the preference.
if (typeof window !== 'undefined') {
  window.addEventListener('storage', e => {
    if (e.key === THEME_STORAGE_KEY) {
      preference = read()
      apply()
      emit()
    }
  })
}

/** Call once at start-up (main.tsx); idempotent. */
export function initTheme() {
  preference = read()
  apply()
}

/** React binding: `[preference, resolved, setPreference]`. */
export function useTheme(): { preference: ThemePreference; resolved: ResolvedTheme; setPreference: typeof setPreference } {
  const p = useSyncExternalStore(subscribe, getPreference, getPreference)
  const resolved = useSyncExternalStore(subscribe, () => resolveTheme(), () => 'light' as ResolvedTheme)
  return { preference: p, resolved, setPreference }
}
