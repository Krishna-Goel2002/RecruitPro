import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import { SummaryStrip } from './SummaryStrip'

describe('<SummaryStrip>', () => {
  it('renders each tile with its label, final value (accessible text) and hint', () => {
    render(
      <MemoryRouter>
        <SummaryStrip label="Jobs at a glance" items={[{ key: 'a', label: 'Open jobs', value: 3, hint: 'Accepting applications' }, { key: 'b', label: 'Best', value: 86, format: n => `${n}%` }]} />
      </MemoryRouter>,
    )
    const list = screen.getByRole('list', { name: 'Jobs at a glance' })
    expect(list).toHaveTextContent('Open jobs')
    expect(list).toHaveTextContent('Accepting applications')
    expect(screen.getAllByText('86%').length).toBeGreaterThan(0)
  })

  it('unknown values show a dash, not zero', () => {
    render(<SummaryStrip label="x" items={[{ key: 'a', label: 'Drafts', value: undefined }]} />)
    expect(screen.getByText('—')).toBeInTheDocument()
  })

  it('a tile with onSelect is a toggle button with aria-pressed; one with `to` is a link', () => {
    const onSelect = vi.fn()
    render(
      <MemoryRouter>
        <SummaryStrip label="x" items={[
          { key: 'a', label: 'Drafts', value: 2, onSelect, pressed: true, actionLabel: 'Show drafts' },
          { key: 'b', label: 'Inbox', value: 1, to: '/notifications' },
        ]} />
      </MemoryRouter>,
    )
    const toggle = screen.getByRole('button', { name: 'Show drafts' })
    expect(toggle).toHaveAttribute('aria-pressed', 'true')
    fireEvent.click(toggle)
    expect(onSelect).toHaveBeenCalledTimes(1)
    expect(screen.getByRole('link')).toHaveAttribute('href', '/notifications')
  })

  it('attention tiles carry a marker (not colour alone: the hint explains)', () => {
    const { container } = render(<SummaryStrip label="x" items={[{ key: 'a', label: 'Closing soon', value: 1, hint: 'Deadline within 7 days', tone: 'attention' }]} />)
    expect(container.querySelector('[data-tone="attention"] .ui-summary-mark')).not.toBeNull()
  })

  it('renders nothing without items', () => {
    const { container } = render(<SummaryStrip label="x" items={[]} />)
    expect(container).toBeEmptyDOMElement()
  })
})
