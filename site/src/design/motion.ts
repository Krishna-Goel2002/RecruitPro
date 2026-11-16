/*
 * Motion helpers (DESIGN_SYSTEM §8.4). Pure CSS does enters/exits; this file covers what CSS cannot:
 * count-up, FLIP reordering, list stagger, scroll reveal and the theme view transition.
 * Every helper checks reduced() first and applies the end state synchronously when it is true.
 */
import { useEffect, useLayoutEffect, useRef, useState, type RefObject } from 'react'

export const reduced = (): boolean =>
  typeof window !== 'undefined' && !!window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches

/** Durations mirrored from tokens.css (JS-driven animations cannot read var() timings cheaply). */
export const DUR = { instant: 80, fast: 140, route: 180, base: 200, slow: 280, slower: 420, count: 700 } as const
export const EASE = {
  standard: 'cubic-bezier(.2, 0, 0, 1)',
  decelerate: 'cubic-bezier(.05, .7, .1, 1)',
  exit: 'cubic-bezier(.4, 0, 1, 1)',
  settle: 'cubic-bezier(.34, 1.36, .64, 1)',
  emphasized: 'cubic-bezier(.16, 1, .3, 1)',
  spring: 'cubic-bezier(.3, 1.24, .52, 1)',
} as const

/** Live `prefers-reduced-motion` (re-renders when the OS setting changes). */
export function useReducedMotion(): boolean {
  const [value, setValue] = useState(reduced)
  useEffect(() => {
    if (typeof window === 'undefined' || !window.matchMedia) return
    const mq = window.matchMedia('(prefers-reduced-motion: reduce)')
    const on = () => setValue(mq.matches)
    mq.addEventListener?.('change', on)
    return () => mq.removeEventListener?.('change', on)
  }, [])
  return value
}

/** Cubic-bezier easing for rAF tweens (Newton-Raphson on x, ~1e-4 accuracy). */
export function bezier(x1: number, y1: number, x2: number, y2: number): (t: number) => number {
  const cx = 3 * x1, bx = 3 * (x2 - x1) - cx, ax = 1 - cx - bx
  const cy = 3 * y1, by = 3 * (y2 - y1) - cy, ay = 1 - cy - by
  const sx = (t: number) => ((ax * t + bx) * t + cx) * t
  const sy = (t: number) => ((ay * t + by) * t + cy) * t
  const dx = (t: number) => (3 * ax * t + 2 * bx) * t + cx
  return (x: number) => {
    if (x <= 0) return 0
    if (x >= 1) return 1
    let t = x
    for (let i = 0; i < 6; i++) {
      const d = dx(t)
      if (Math.abs(d) < 1e-6) break
      t -= (sx(t) - x) / d
    }
    return sy(Math.min(1, Math.max(0, t)))
  }
}
const decelerate = bezier(0.05, 0.7, 0.1, 1)

const running = new WeakMap<HTMLElement, number>()

/** Tween the text of `el` from `from` to `to` (tabular numerals expected on the element). */
export function countUp(
  el: HTMLElement,
  to: number,
  opts: { from?: number; ms?: number; format?: (n: number) => string } = {},
): void {
  const { from = 0, ms = DUR.count, format = (n: number) => String(Math.round(n)) } = opts
  const prev = running.get(el)
  if (prev) cancelAnimationFrame(prev)
  if (reduced() || ms <= 0 || from === to) {
    el.textContent = format(to)
    return
  }
  const start = performance.now()
  const step = (now: number) => {
    const p = Math.min(1, (now - start) / ms)
    el.textContent = format(from + (to - from) * decelerate(p))
    if (p < 1) running.set(el, requestAnimationFrame(step))
    else running.delete(el)
  }
  el.textContent = format(from)
  running.set(el, requestAnimationFrame(step))
}

function flipRects(container: HTMLElement): Map<string, DOMRect> {
  const map = new Map<string, DOMRect>()
  container.querySelectorAll<HTMLElement>('[data-flip-id]').forEach(n => map.set(n.dataset.flipId!, n.getBoundingClientRect()))
  return map
}

function playFlip(container: HTMLElement, before: Map<string, DOMRect>, ms: number): Promise<void> {
  const anims: Animation[] = []
  container.querySelectorAll<HTMLElement>('[data-flip-id]').forEach(n => {
    const old = before.get(n.dataset.flipId!)
    if (!old) return
    const now = n.getBoundingClientRect()
    const dx = old.left - now.left
    const dy = old.top - now.top
    if (Math.abs(dx) < 0.5 && Math.abs(dy) < 0.5) return
    anims.push(
      n.animate([{ transform: `translate(${dx}px, ${dy}px)` }, { transform: 'translate(0, 0)' }], {
        duration: ms,
        easing: EASE.standard,
      }),
    )
  })
  return Promise.all(anims.map(a => a.finished.catch(() => undefined))).then(() => undefined)
}

/** FLIP: children keyed by [data-flip-id] glide from their old to their new positions after a synchronous mutate(). */
export function flip(container: HTMLElement, mutate: () => void, opts: { ms?: number } = {}): Promise<void> {
  if (reduced()) {
    mutate()
    return Promise.resolve()
  }
  const before = flipRects(container)
  mutate()
  return playFlip(container, before, opts.ms ?? DUR.slow)
}

/**
 * React FLIP (M7): call in the component that renders the [data-flip-id] children; rows animate whenever `key`
 * (for example the joined row order) changes between renders.
 */
export function useFlip(ref: RefObject<HTMLElement>, key: unknown, opts: { ms?: number } = {}) {
  const last = useRef<Map<string, DOMRect> | null>(null)
  const lastKey = useRef(key)
  useLayoutEffect(() => {
    const el = ref.current
    if (!el) return
    if (last.current && lastKey.current !== key && !reduced()) void playFlip(el, last.current, opts.ms ?? DUR.slow)
    lastKey.current = key
    last.current = flipRects(el)
  })
}

/**
 * List entrance (M2): sets --i on the first `max` items (the rest share the last delay) and adds .is-entering,
 * removed when the animation ends. Use on FIRST load only, never on refetch or filtering.
 */
export function stagger(items: Iterable<HTMLElement>, opts: { step?: number; max?: number } = {}): void {
  if (reduced()) return
  const { step = 24, max = 8 } = opts
  let i = 0
  for (const el of items) {
    el.style.setProperty('--i', String(Math.min(i, max - 1)))
    el.style.setProperty('--stagger-step', `${step}ms`)
    el.classList.add('is-entering')
    el.addEventListener('animationend', () => el.classList.remove('is-entering'), { once: true })
    i++
  }
}

/** Runs `fn` once when `el` first scrolls into view (or immediately under reduced motion / without IO). */
export function reveal(el: Element, fn: () => void, opts: { threshold?: number; rootMargin?: string } = {}): () => void {
  if (reduced() || typeof IntersectionObserver === 'undefined') {
    fn()
    return () => undefined
  }
  const io = new IntersectionObserver(
    entries => {
      if (entries.some(e => e.isIntersecting)) {
        io.disconnect()
        fn()
      }
    },
    { threshold: opts.threshold ?? 0.2, rootMargin: opts.rootMargin ?? '0px 0px -8% 0px' },
  )
  io.observe(el)
  return () => io.disconnect()
}

/** True once the element has been in view (stays true). */
export function useInView<T extends Element>(ref: RefObject<T>, opts: { threshold?: number } = {}): boolean {
  const [seen, setSeen] = useState(false)
  useEffect(() => {
    const el = ref.current
    if (!el || seen) return
    return reveal(el, () => setSeen(true), opts)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ref, seen])
  return seen
}

/**
 * Reveal on first view (motion v2): the element fades and rises 8 px the first time it scrolls into view. The hidden
 * state is set from JS only when the reveal will run, so content is never stuck hidden (no JS, no IO, reduced motion).
 * `delay` staggers siblings (ms).
 */
export function useReveal<T extends HTMLElement>(ref: RefObject<T>, opts: { delay?: number; threshold?: number } = {}): void {
  useLayoutEffect(() => {
    const el = ref.current
    if (!el || reduced() || typeof IntersectionObserver === 'undefined') return
    el.dataset.reveal = 'hidden'
    if (opts.delay) el.style.setProperty('--reveal-delay', `${opts.delay}ms`)
    let frame = 0
    const stop = reveal(
      el,
      () => {
        frame = requestAnimationFrame(() => {
          el.dataset.reveal = 'shown'
        })
      },
      { threshold: opts.threshold ?? 0.1, rootMargin: '0px' },
    )
    return () => {
      stop()
      cancelAnimationFrame(frame)
      delete el.dataset.reveal
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ref])
}

/**
 * Grow on first view (motion v2): toggles data-grow "pending" → "done" so a bar or ladder sweeps in from the left
 * (clip-path, motion.css). Returns nothing; reduced motion leaves the attribute off (final state immediately).
 */
export function useGrowOnView<T extends HTMLElement>(ref: RefObject<T>, enabled = true): void {
  useLayoutEffect(() => {
    const el = ref.current
    if (!el || !enabled || reduced() || typeof IntersectionObserver === 'undefined') return
    el.dataset.grow = 'pending'
    let frame = 0
    const stop = reveal(el, () => {
      frame = requestAnimationFrame(() => {
        el.dataset.grow = 'done'
      })
    })
    return () => {
      stop()
      cancelAnimationFrame(frame)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ref, enabled])
}

/**
 * Stagger the children matching `selector` inside `ref` once, the first time `ready` is true (first load only; never
 * on refetch or filter changes, §8.3 rule 4).
 */
export function useStaggerOnce<T extends HTMLElement>(ref: RefObject<T>, ready: boolean, selector = ':scope > *', opts: { step?: number; max?: number } = {}): void {
  const done = useRef(false)
  useLayoutEffect(() => {
    if (done.current || !ready || !ref.current) return
    done.current = true
    stagger(ref.current.querySelectorAll<HTMLElement>(selector), opts)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ready])
}

type ViewTransitionDoc = Document & { startViewTransition?: (cb: () => void) => { ready: Promise<void> } }

/**
 * Theme switch (M14). With an origin and View Transitions support, the new theme is revealed by a circular clip-path
 * growing from the origin over 360 ms; otherwise a 200 ms colour crossfade (html.theme-switching); reduced: instant.
 */
export function withViewTransition(update: () => void, origin?: { x: number; y: number }): void {
  const root = document.documentElement
  if (reduced()) {
    update()
    return
  }
  const doc = document as ViewTransitionDoc
  if (!doc.startViewTransition || !origin) {
    root.classList.add('theme-switching')
    update()
    window.setTimeout(() => root.classList.remove('theme-switching'), 250)
    return
  }
  const { x, y } = origin
  const r = Math.hypot(Math.max(x, window.innerWidth - x), Math.max(y, window.innerHeight - y))
  const vt = doc.startViewTransition(update)
  vt.ready
    .then(() => {
      root.animate(
        { clipPath: [`circle(0px at ${x}px ${y}px)`, `circle(${r}px at ${x}px ${y}px)`] },
        { duration: 360, easing: EASE.standard, pseudoElement: '::view-transition-new(root)' },
      )
    })
    .catch(() => undefined)
}
