import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import { detail } from '../../test/fixtures'
import { DecisionBar } from './DecisionBar'
import { primaryFor } from './model'

function bar(over: Parameters<typeof detail>[1], { canDecide = true, canSchedule = true } = {}) {
  const onAction = vi.fn()
  const onSchedule = vi.fn()
  render(<MemoryRouter><DecisionBar detail={detail(3001, over)} canDecide={canDecide} canSchedule={canSchedule} onAction={onAction} onSchedule={onSchedule} onHelp={vi.fn()} /></MemoryRouter>)
  return { onAction, onSchedule }
}

describe('decision bar', () => {
  it('SUBMITTED: Shortlist is the primary, Reject is separate', () => {
    const { onAction } = bar({ applicationStatus: 'SUBMITTED', allowedActions: ['SHORTLIST', 'REJECT'] })
    fireEvent.click(screen.getByRole('button', { name: 'Shortlist' }))
    expect(onAction).toHaveBeenCalledWith('SHORTLIST')
    fireEvent.click(screen.getByRole('button', { name: 'Reject' }))
    expect(onAction).toHaveBeenCalledWith('REJECT')
  })

  it('SHORTLISTED: Schedule interview is the primary for schedulers; Undo shortlist is under More', () => {
    const { onSchedule } = bar({ applicationStatus: 'SHORTLISTED', allowedActions: ['UNDO_SHORTLIST', 'MOVE_TO_INTERVIEW', 'REJECT'] })
    fireEvent.click(screen.getByRole('button', { name: 'Schedule interview' }))
    expect(onSchedule).toHaveBeenCalled()
    expect(screen.getByRole('button', { name: 'More decisions' })).toBeInTheDocument()
  })

  it('REJECTED: Reopen is offered; recruiters only see the summary', () => {
    const { onAction } = bar({ applicationStatus: 'REJECTED', allowedActions: ['REOPEN'] })
    fireEvent.click(screen.getByRole('button', { name: 'Reopen' }))
    expect(onAction).toHaveBeenCalledWith('REOPEN')
  })

  it('recruiters see who decides, and no decision buttons', () => {
    bar({ applicationStatus: 'SUBMITTED', allowedActions: ['SHORTLIST', 'REJECT'] }, { canDecide: false, canSchedule: false })
    expect(screen.getByText(/HR managers make decisions/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Shortlist' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Reject' })).not.toBeInTheDocument()
  })

  it('final states show the outcome only', () => {
    bar({ applicationStatus: 'SELECTED', allowedActions: [] })
    expect(screen.getByText(/handed off to your offer process/)).toBeInTheDocument()
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
  })

  it('never offers an action the server did not allow', () => {
    expect(primaryFor('SUBMITTED', [], true)).toBeNull()
    expect(primaryFor('INTERVIEWING', ['REJECT'], true)).toBeNull()
    expect(primaryFor('INTERVIEWING', ['SELECT', 'REJECT'], true)).toBe('SELECT')
  })
})
