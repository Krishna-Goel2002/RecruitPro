/*
 * Floating-element positioning (tooltips, menus, popovers) with position: fixed, flip and viewport clamping.
 * Floating layers are portalled to the nearest open <dialog> (so they render inside the top layer) or <body>.
 */
import { useLayoutEffect, useState, type CSSProperties } from 'react'

export type Side = 'top' | 'bottom'
export type Align = 'start' | 'center' | 'end'

export function portalTarget(anchor: Element | null): Element {
  return (anchor?.closest('dialog[open]') as Element | null) ?? document.body
}

export function computePosition(
  anchor: DOMRect,
  floating: { width: number; height: number },
  side: Side,
  align: Align,
  offset = 6,
): { top: number; left: number; side: Side } {
  const vw = window.innerWidth
  const vh = window.innerHeight
  const margin = 8
  let s = side
  if (s === 'top' && anchor.top - floating.height - offset < margin) s = 'bottom'
  else if (s === 'bottom' && anchor.bottom + floating.height + offset > vh - margin && anchor.top - floating.height - offset > margin) s = 'top'
  const top = s === 'top' ? anchor.top - floating.height - offset : anchor.bottom + offset
  let left =
    align === 'start' ? anchor.left : align === 'end' ? anchor.right - floating.width : anchor.left + anchor.width / 2 - floating.width / 2
  left = Math.max(margin, Math.min(left, vw - floating.width - margin))
  return { top: Math.round(top), left: Math.round(left), side: s }
}

/** Keeps `floating` positioned against `anchor` while open (re-measures on scroll and resize). */
export function useFloating(
  anchor: HTMLElement | null,
  floating: HTMLElement | null,
  open: boolean,
  side: Side = 'bottom',
  align: Align = 'start',
  offset = 6,
): { style: CSSProperties; side: Side } {
  const [pos, setPos] = useState<{ top: number; left: number; side: Side } | null>(null)
  useLayoutEffect(() => {
    if (!open || !anchor || !floating) {
      setPos(null)
      return
    }
    const update = () => {
      const r = floating.getBoundingClientRect()
      setPos(computePosition(anchor.getBoundingClientRect(), { width: r.width, height: r.height }, side, align, offset))
    }
    update()
    window.addEventListener('scroll', update, true)
    window.addEventListener('resize', update)
    return () => {
      window.removeEventListener('scroll', update, true)
      window.removeEventListener('resize', update)
    }
  }, [open, anchor, floating, side, align, offset])
  return {
    style: pos ? { position: 'fixed', top: pos.top, left: pos.left } : { position: 'fixed', top: -9999, left: -9999 },
    side: pos?.side ?? side,
  }
}
