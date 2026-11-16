import { useState } from 'react'
import { fireEvent, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { Link } from 'react-router-dom'
import { renderApp } from '../test/render'
import { useDiscardGuard, useLeaveGuard } from './leaveGuard'

function Editor({ dirty }: { dirty: boolean }) {
  const guard = useLeaveGuard(dirty)
  return (
    <>
      <Link to="/elsewhere">Go elsewhere</Link>
      <a href="https://example.com/docs" target="_blank" rel="noreferrer">External docs</a>
      {guard}
    </>
  )
}

describe('useLeaveGuard', () => {
  it('holds an in-app link while there are unsaved changes; Keep editing stays', async () => {
    renderApp(<Editor dirty />, { route: '/jobs/1/edit' })
    fireEvent.click(screen.getByRole('link', { name: 'Go elsewhere' }))
    const dialog = await screen.findByRole('alertdialog', { name: 'Leave without saving?' })
    expect(screen.getByTestId('location')).toHaveTextContent('/jobs/1/edit')
    fireEvent.click(within(dialog).getByRole('button', { name: 'Keep editing' }))
    expect(screen.getByTestId('location')).toHaveTextContent('/jobs/1/edit')
  })

  it('Leave without saving replays the navigation', async () => {
    renderApp(<Editor dirty />, { route: '/jobs/1/edit' })
    fireEvent.click(screen.getByRole('link', { name: 'Go elsewhere' }))
    const dialog = await screen.findByRole('alertdialog', { name: 'Leave without saving?' })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Leave without saving' }))
    expect(screen.getByTestId('location')).toHaveTextContent('/elsewhere')
  })

  it('does nothing without changes, and ignores links that open elsewhere', () => {
    const { unmount } = renderApp(<Editor dirty={false} />, { route: '/jobs/1/edit' })
    fireEvent.click(screen.getByRole('link', { name: 'Go elsewhere' }))
    expect(screen.getByTestId('location')).toHaveTextContent('/elsewhere')
    unmount()
    renderApp(<Editor dirty />, { route: '/jobs/1/edit' })
    fireEvent.click(screen.getByRole('link', { name: 'External docs' }))
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
  })
})

function Drawerish({ dirty }: { dirty: boolean }) {
  const [closed, setClosed] = useState(false)
  const guard = useDiscardGuard(dirty, () => setClosed(true), 'note')
  return (
    <>
      <button type="button" onClick={guard.request}>Close</button>
      {closed && <p>Closed</p>}
      {guard.dialog}
    </>
  )
}

describe('useDiscardGuard', () => {
  it('closes at once when nothing changed, and asks first when something did', async () => {
    const { unmount } = renderApp(<Drawerish dirty={false} />)
    fireEvent.click(screen.getByRole('button', { name: 'Close' }))
    expect(screen.getByText('Closed')).toBeInTheDocument()
    unmount()
    renderApp(<Drawerish dirty />)
    fireEvent.click(screen.getByRole('button', { name: 'Close' }))
    const dialog = await screen.findByRole('alertdialog', { name: 'Discard your note?' })
    expect(screen.queryByText('Closed')).not.toBeInTheDocument()
    fireEvent.click(within(dialog).getByRole('button', { name: 'Discard' }))
    expect(screen.getByText('Closed')).toBeInTheDocument()
  })
})
