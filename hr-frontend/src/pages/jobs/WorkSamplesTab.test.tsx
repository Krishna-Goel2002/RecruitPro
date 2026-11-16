import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { assignment, job } from '../../test/fixtures'
import { renderApp } from '../../test/render'
import { WorkSamplesTab } from './WorkSamplesTab'

vi.mock('../../api/assessments', () => ({
  workSamplesApi: {
    list: vi.fn(), generate: vi.fn(), update: vi.fn(), setStatus: vi.fn(), assign: vi.fn(), assignments: vi.fn(), score: vi.fn(), cancel: vi.fn(), downloadFile: vi.fn(),
  },
}))
import { workSamplesApi } from '../../api/assessments'

beforeEach(() => {
  vi.mocked(workSamplesApi.list).mockResolvedValue([])
  vi.mocked(workSamplesApi.assignments).mockResolvedValue([assignment(71), assignment(72, { applicationId: 3002, status: 'CANCELLED', files: [] })])
  vi.mocked(workSamplesApi.cancel).mockImplementation(async id => assignment(id, { status: 'CANCELLED' }))
  vi.mocked(workSamplesApi.downloadFile).mockResolvedValue('solution.zip')
})

async function openActions(applicationId: number) {
  fireEvent.click(await screen.findByRole('button', { name: `Actions for application #A-${applicationId}` }))
  return screen.findByRole('menu')
}

describe('job › Work samples › assignments: cancel and download', () => {
  it('downloads a submitted file through the audited endpoint', async () => {
    renderApp(<WorkSamplesTab job={job()} />, { role: 'RECRUITER' })
    const menu = await openActions(3001)
    fireEvent.click(within(menu).getByRole('menuitem', { name: /Download solution\.zip/ }))
    await waitFor(() => expect(workSamplesApi.downloadFile).toHaveBeenCalledWith(71, 501, 'solution.zip'))
    expect(await screen.findByText(/Recorded in the audit log/)).toBeInTheDocument()
  })

  it('recruiters cannot cancel (the server would answer 403)', async () => {
    renderApp(<WorkSamplesTab job={job()} />, { role: 'RECRUITER' })
    const menu = await openActions(3001)
    expect(within(menu).queryByRole('menuitem', { name: /Cancel assignment/ })).not.toBeInTheDocument()
  })

  it('cancel asks for a reason first, then sends it and refreshes the lists', async () => {
    const view = renderApp(<WorkSamplesTab job={job()} />, { role: 'HR_MANAGER' })
    const invalidate = vi.spyOn(view.queryClient, 'invalidateQueries')
    const menu = await openActions(3001)
    fireEvent.click(within(menu).getByRole('menuitem', { name: /Cancel assignment/ }))
    const dialog = await screen.findByRole('alertdialog', { name: 'Cancel this assignment?' })
    expect(document.activeElement).toBe(within(dialog).getByRole('button', { name: 'Cancel' }))
    fireEvent.click(within(dialog).getByRole('button', { name: 'Cancel assignment' }))
    expect(await within(dialog).findByText('A reason is required to cancel.')).toBeInTheDocument()
    expect(workSamplesApi.cancel).not.toHaveBeenCalled()
    fireEvent.change(within(dialog).getByRole('textbox'), { target: { value: '  Role filled internally  ' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Cancel assignment' }))
    await waitFor(() => expect(workSamplesApi.cancel).toHaveBeenCalledWith(71, 'Role filled internally'))
    expect(await screen.findByText('Assignment cancelled')).toBeInTheDocument()
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['workSamples', 101, 'assignments'] })
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['applicants', 101, 'detail', 3001] })
  })

  it('cancelled assignments offer no cancel', async () => {
    renderApp(<WorkSamplesTab job={job()} />, { role: 'HR_MANAGER' })
    const menu = await openActions(3002)
    expect(within(menu).queryByRole('menuitem', { name: /Cancel assignment/ })).not.toBeInTheDocument()
  })
})
