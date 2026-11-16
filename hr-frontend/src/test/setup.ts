import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach, vi } from 'vitest'

/* Browser APIs jsdom does not implement, kept minimal and deterministic. */

afterEach(() => {
  cleanup()
  try { window.localStorage.clear() } catch { /* ignore */ }
})

if (!window.matchMedia) {
  // Reduced motion on (stable, no animations); everything else false.
  window.matchMedia = ((query: string) => ({
    matches: /prefers-reduced-motion:\s*reduce/.test(query),
    media: query,
    onchange: null,
    addListener: () => undefined,
    removeListener: () => undefined,
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
    dispatchEvent: () => false,
  })) as unknown as typeof window.matchMedia
}

class NoopObserver {
  observe() { /* no layout in jsdom */ }
  unobserve() { /* no layout in jsdom */ }
  disconnect() { /* no layout in jsdom */ }
  takeRecords() { return [] }
}
if (!('ResizeObserver' in window)) (window as unknown as { ResizeObserver: unknown }).ResizeObserver = NoopObserver
if (!('IntersectionObserver' in window)) (window as unknown as { IntersectionObserver: unknown }).IntersectionObserver = NoopObserver

if (!Element.prototype.scrollIntoView) Element.prototype.scrollIntoView = function scrollIntoView() { /* no layout */ }
if (!Element.prototype.animate) {
  Element.prototype.animate = function animate() {
    return { finished: Promise.resolve(), cancel: () => undefined, onfinish: null } as unknown as Animation
  }
}

// <dialog>: jsdom lacks showModal/close; emulate the open attribute and the close event.
const dialogProto = (window.HTMLDialogElement ?? HTMLElement).prototype as HTMLDialogElement
if (!dialogProto.showModal) {
  dialogProto.showModal = function showModal(this: HTMLDialogElement) { this.setAttribute('open', '') }
  dialogProto.show = function show(this: HTMLDialogElement) { this.setAttribute('open', '') }
  dialogProto.close = function close(this: HTMLDialogElement) {
    this.removeAttribute('open')
    this.dispatchEvent(new Event('close'))
  }
}

if (!URL.createObjectURL) URL.createObjectURL = vi.fn(() => 'blob:test')
if (!URL.revokeObjectURL) URL.revokeObjectURL = vi.fn()
