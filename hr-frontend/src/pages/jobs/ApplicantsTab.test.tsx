import { act, fireEvent, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushDeferredCommits } from '../../lib/deferredCommit'
import { detail, job, page, summary } from '../../test/fixtures'
import { renderApp } from '../../test/render'
import { ApplicantsTab } from './ApplicantsTab'

vi.mock('../../api/review', () => ({
  reviewApi: {
    list: vi.fn(),
    detail: vi.fn(),
    decide: vi.fn(),
    rematch: vi.fn(),
  },
}))
import { reviewApi } from '../../api/review'

const rows = [summary(3001), summary(3002), summary(3003, { applicationStatus: 'SHORTLISTED', identityRevealed: true, displayName: 'Asha Rao', rank: 3 })]

beforeEach(() => {
  vi.useFakeTimers({ shouldAdvanceTime: true })
  vi.mocked(reviewApi.list).mockResolvedValue(page(rows))
  vi.mocked(reviewApi.detail).mockImplementation(async (_job, id) => detail(id, { applicationStatus: rows.find(r => r.applicationId === id)!.applicationStatus, allowedActions: id === 3003 ? ['REJECT', 'MOVE_TO_INTERVIEW'] : ['SHORTLIST', 'REJECT'] }))
  vi.mocked(reviewApi.decide).mockResolvedValue({} as never)
})
afterEach(async () => {
  await act(async () => { await flushDeferredCommits() })
  vi.useRealTimers()
})

const press = (key: string) => fireEvent.keyDown(document.body, { key, bubbles: true, cancelable: true })

async function setup(role: 'HR_MANAGER' | 'RECRUITER' = 'HR_MANAGER') {
  const view = renderApp(<ApplicantsTab job={job()} />, { route: '/jobs/101', role })
  await screen.findByRole('link', { name: 'Candidate #A-3001' })
  return view
}

describe('ranking: keyboard triage and undo', () => {
  it('j moves to the first row, s asks first, then the status flips and Undo sends nothing', async () => {
    await setup()
    press('j')
    expect(document.activeElement?.closest('tr')?.getAttribute('data-row')).toBe('3001')
    press('s')
    const dialog = await screen.findByRole('alertdialog', { name: /Shortlist Candidate #A-3001\?/ })
    expect(within(dialog).getByText(/Name and contact details/)).toBeInTheDocument()
    fireEvent.click(within(dialog).getByRole('button', { name: 'Shortlist and reveal' }))
    const undo = await screen.findByRole('button', { name: 'Undo' })
    const row = document.querySelector('tr[data-row="3001"]') as HTMLElement
    expect(within(row).getByText(/Shortlisted/i)).toBeInTheDocument()
    fireEvent.click(undo)
    await act(async () => { vi.advanceTimersByTime(6000) })
    expect(reviewApi.decide).not.toHaveBeenCalled()
    expect(within(row).queryByText(/Shortlisted/i)).not.toBeInTheDocument()
  })

  it('sends the decision once the 5-second window has passed', async () => {
    await setup()
    press('j')
    press('r')
    const dialog = await screen.findByRole('alertdialog', { name: /not progressed/i })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Must-have not evidenced after review.' }))
    fireEvent.click(within(dialog).getByRole('button', { name: 'Not progressed' }))
    await act(async () => { vi.advanceTimersByTime(4000) })
    expect(reviewApi.decide).not.toHaveBeenCalled()
    await act(async () => { vi.advanceTimersByTime(1500) })
    await waitFor(() => expect(reviewApi.decide).toHaveBeenCalledWith(101, 3001, { action: 'REJECT', comment: 'Must-have not evidenced after review.' }))
  })

  it('shortcuts never reach the page while the confirmation is open (no switching s ↔ r)', async () => {
    await setup()
    press('j')
    press(' ') // quick peek (a drawer)
    await screen.findByRole('dialog', { name: /Candidate #A-3001/ })
    press('s') // allowed inside the peek
    await screen.findByRole('alertdialog', { name: /Shortlist Candidate #A-3001\?/ })
    press('r') // must NOT replace the shortlist confirmation with a reject one
    expect(screen.queryByRole('alertdialog', { name: /not progressed/i })).not.toBeInTheDocument()
    expect(screen.getByRole('alertdialog', { name: /Shortlist Candidate #A-3001\?/ })).toBeInTheDocument()
  })

  it('only offers decisions the status allows (s on a shortlisted row explains instead of asking)', async () => {
    await setup()
    press('j'); press('j'); press('j')
    expect(document.activeElement?.closest('tr')?.getAttribute('data-row')).toBe('3003')
    press('s')
    expect(await screen.findByText('Nothing to shortlist')).toBeInTheDocument()
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
  })

  it('recruiters can move and peek but never decide', async () => {
    await setup('RECRUITER')
    press('j')
    press('s')
    press('r')
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    expect(screen.queryByText(/shortlist$/i, { selector: '.hra-kbd-hints *' })).not.toBeInTheDocument()
  })

  it('x selects rows and c opens the compare view in the URL', async () => {
    await setup()
    press('j'); press('x'); press('j'); press('x')
    press('c')
    await waitFor(() => expect(screen.getByTestId('location').textContent).toMatch(/compare=3001%2C3002|compare=3001,3002/))
    expect(await screen.findByRole('dialog', { name: 'Compare 2 applicants' })).toBeInTheDocument()
  })
})
