import { fireEvent, render } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { isTypingTarget, useShortcuts } from './Shortcuts'

function Page({ map, inDialog = [] }: { map: Record<string, () => void>; inDialog?: string[] }) {
  useShortcuts(map, true, inDialog)
  return (
    <div>
      <input aria-label="search" />
      <textarea aria-label="note" />
      <input type="checkbox" aria-label="select row" />
      <button type="button">A button</button>
      <div role="menu"><div role="menuitem" tabIndex={-1}>Item</div></div>
    </div>
  )
}

const key = (target: Element | Document, k: string, init: KeyboardEventInit = {}) =>
  fireEvent.keyDown(target, { key: k, bubbles: true, cancelable: true, ...init })

describe('useShortcuts', () => {
  it('fires on the page and never while typing in a field or a menu', () => {
    const j = vi.fn()
    const { getByLabelText, getByRole } = render(<Page map={{ j }} />)
    key(document.body, 'j')
    expect(j).toHaveBeenCalledTimes(1)
    key(getByLabelText('search'), 'j')
    key(getByLabelText('note'), 'j')
    key(getByRole('menuitem'), 'j')
    expect(j).toHaveBeenCalledTimes(1)
  })

  it('ignores modifier combinations (Ctrl+S, Cmd+K…)', () => {
    const s = vi.fn()
    render(<Page map={{ s }} />)
    key(document.body, 's', { ctrlKey: true })
    key(document.body, 's', { metaKey: true })
    key(document.body, 's', { altKey: true })
    expect(s).not.toHaveBeenCalled()
  })

  it('letters still work on a focused checkbox, but Space keeps toggling it', () => {
    const x = vi.fn()
    const space = vi.fn()
    const { getByLabelText, getByRole } = render(<Page map={{ x, ' ': space }} />)
    const box = getByLabelText('select row')
    key(box, 'x')
    expect(x).toHaveBeenCalledTimes(1)
    const event = key(box, ' ')
    expect(space).not.toHaveBeenCalled()
    expect(event).toBe(true) // not prevented: the checkbox toggles natively
    key(getByRole('button', { name: 'A button' }), ' ')
    expect(space).not.toHaveBeenCalled()
  })

  it('is silent behind a dialog, except allowed keys while exactly one dialog is open', () => {
    const j = vi.fn()
    const s = vi.fn()
    render(<Page map={{ j, s }} inDialog={['j']} />)
    const drawer = document.createElement('dialog')
    drawer.setAttribute('open', '')
    document.body.appendChild(drawer)
    key(document.body, 'j')
    key(document.body, 's')
    expect(j).toHaveBeenCalledTimes(1)
    expect(s).not.toHaveBeenCalled()
    // A confirmation on top of the drawer: nothing reaches the page.
    const confirm = document.createElement('dialog')
    confirm.setAttribute('open', '')
    document.body.appendChild(confirm)
    key(document.body, 'j')
    expect(j).toHaveBeenCalledTimes(1)
    drawer.remove()
    confirm.remove()
  })

  it('isTypingTarget treats radios and checkboxes as non-typing', () => {
    const radio = document.createElement('input')
    radio.type = 'radio'
    const text = document.createElement('input')
    expect(isTypingTarget(radio)).toBe(false)
    expect(isTypingTarget(text)).toBe(true)
  })
})
