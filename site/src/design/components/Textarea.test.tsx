import { fireEvent, render, screen } from '@testing-library/react'
import { beforeAll, describe, expect, it } from 'vitest'
import { Field } from './Field'
import { Textarea } from './Textarea'

let componentsCss = ''
beforeAll(async () => {
  // Read the stylesheet from disk (CSS imports are stubbed in tests). Non-literal specifier: no Node types needed.
  const fs = await import(/* @vite-ignore */ 'node:fs/promises' as string)
  const cwd = (globalThis as unknown as { process: { cwd: () => string } }).process.cwd()
  componentsCss = await fs.readFile(`${cwd}/src/design/components.css`, 'utf8')
})

/** The rule body for an exact selector in components.css (first match). */
function rule(selector: string): string {
  const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const m = new RegExp(`(?:^|\\n)${escaped}\\s*\\{([^}]*)\\}`).exec(componentsCss)
  return m ? m[1] : ''
}

describe('textarea scrolling (owner bug: long work-sample instructions could not be scrolled)', () => {
  it('every design-system textarea scrolls vertically, can be resized and is capped in height', () => {
    const base = rule('textarea.ui-input')
    expect(base).toMatch(/overflow-y:\s*auto/)
    expect(base).toMatch(/resize:\s*vertical/)
    expect(base).toMatch(/max-height:/)
    // The thumb is drawn in the stronger scrollbar colour, so a field that scrolls looks scrollable.
    expect(base).toMatch(/scrollbar-color:\s*var\(--scrollbar-hover\)/)
  })

  it('auto-growing textareas are not clipped either', () => {
    const grow = rule('textarea.ui-input[data-autogrow]')
    expect(grow).not.toMatch(/overflow-y:\s*hidden/)
    expect(grow).toMatch(/overflow-y:\s*auto/)
    expect(componentsCss).not.toMatch(/textarea[^{]*\{[^}]*resize:\s*none/)
  })

  it('drawer and dialog bodies scroll', () => {
    expect(rule('.ui-drawer-body')).toMatch(/overflow:\s*auto/)
    expect(rule('.ui-dialog-body')).toMatch(/overflow:\s*auto/)
  })

  it('never sets an inline overflow: hidden while typing long text', () => {
    render(
      <Field label="Instructions">
        <Textarea rows={2} maxRows={4} defaultValue="" />
      </Field>,
    )
    const box = screen.getByLabelText('Instructions') as HTMLTextAreaElement
    fireEvent.change(box, { target: { value: 'line\n'.repeat(60) } })
    expect(box.style.overflowY).not.toBe('hidden')
    expect(box).toHaveAttribute('data-autogrow')
  })
})
