/**
 * Vitest + Testing Library setup (jsdom). jsdom lacks a few browser APIs the design system uses; the stubs below
 * are the smallest stand-ins that keep components behaving like in a browser with reduced motion.
 */
import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach, vi } from 'vitest'

afterEach(() => {
  cleanup()
  window.localStorage.clear()
  window.sessionStorage.clear()
})

// Reduced motion (no animations to wait for) and a desktop-width viewport by default.
if (!window.matchMedia) {
  Object.defineProperty(window, 'matchMedia', {
    writable: true,
    configurable: true,
    value: (query: string) => ({
      matches: /prefers-reduced-motion:\s*reduce/.test(query) || /min-width/.test(query),
      media: query,
      onchange: null,
      addEventListener: () => undefined,
      removeEventListener: () => undefined,
      addListener: () => undefined,
      removeListener: () => undefined,
      dispatchEvent: () => false,
    }),
  })
}

// Native <dialog>: jsdom has the element but not the modal methods.
const dialogProto = HTMLDialogElement.prototype as HTMLDialogElement & { showModal?: () => void; close?: () => void }
{
  dialogProto.showModal = function showModal(this: HTMLDialogElement) {
    this.setAttribute('open', '')
  }
  dialogProto.show = function show(this: HTMLDialogElement) {
    this.setAttribute('open', '')
  }
  dialogProto.close = function close(this: HTMLDialogElement) {
    if (!this.hasAttribute('open')) return
    this.removeAttribute('open')
    this.dispatchEvent(new Event('close'))
  }
}

if (!Element.prototype.scrollIntoView) Element.prototype.scrollIntoView = () => undefined
if (!Element.prototype.animate) {
  Element.prototype.animate = function animate() {
    return { finished: Promise.resolve(), cancel: () => undefined, finish: () => undefined, onfinish: null } as unknown as Animation
  }
}
if (typeof globalThis.CSS === 'undefined') (globalThis as { CSS?: unknown }).CSS = {}
if (!CSS.escape) CSS.escape = (value: string) => value.replace(/[^a-zA-Z0-9_-]/g, ch => `\\${ch}`)
window.scrollTo = vi.fn() as unknown as typeof window.scrollTo
if (!URL.createObjectURL) URL.createObjectURL = () => 'blob:test'
if (!URL.revokeObjectURL) URL.revokeObjectURL = () => undefined
