import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { assignment, job, NOW } from '../../test/fixtures'
import type { WorkSample } from '../../api/types'
import { renderApp } from '../../test/render'
import { WorkSamplesTab } from './WorkSamplesTab'

vi.mock('../../api/assessments', () => ({
  workSamplesApi: {
    list: vi.fn(), generate: vi.fn(), create: vi.fn(), duplicate: vi.fn(), remove: vi.fn(), update: vi.fn(), setStatus: vi.fn(), assign: vi.fn(), assignments: vi.fn(), score: vi.fn(), cancel: vi.fn(), downloadFile: vi.fn(),
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
    expect(document.activeElement).toBe(within(dialog).getByRole('button', { name: 'Keep assignment' }))
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

function sample(id: number, over: Partial<WorkSample> = {}): WorkSample {
  return {
    id, jobId: 101, title: `Task ${id}`, instructions: 'Build an endpoint.', timeLimitMinutes: 30,
    rubric: [{ requirementId: 1, criterion: 'Design', anchors: { '1': 'a', '2': 'b', '3': 'c', '4': 'd' } }],
    status: 'DRAFT', createdAt: NOW, updatedAt: NOW, assignmentCount: 0, ...over,
  }
}

async function openSampleMenu(title: string) {
  fireEvent.click(await screen.findByRole('button', { name: `More actions for ${title}` }))
  return screen.findByRole('menu')
}

describe('job › Work samples › everyday lifecycle (completeness sweep)', () => {
  it('deletes a draft after a confirmation and says it is audited', async () => {
    vi.mocked(workSamplesApi.list).mockResolvedValue([sample(5, { title: 'Draft task' })])
    vi.mocked(workSamplesApi.remove).mockResolvedValue(null)
    renderApp(<WorkSamplesTab job={job()} />, { role: 'HR_MANAGER' })
    const menu = await openSampleMenu('Draft task')
    fireEvent.click(within(menu).getByRole('menuitem', { name: /Delete draft/ }))
    const dialog = await screen.findByRole('alertdialog', { name: 'Delete the draft “Draft task”?' })
    expect(document.activeElement).toBe(within(dialog).getByRole('button', { name: 'Keep draft' }))
    fireEvent.click(within(dialog).getByRole('button', { name: 'Delete draft' }))
    await waitFor(() => expect(workSamplesApi.remove).toHaveBeenCalledWith(101, 5))
    expect(await screen.findByText('Draft “Draft task” deleted')).toBeInTheDocument()
    await waitFor(() => expect(screen.queryByRole('button', { name: /More actions for Draft task/ })).not.toBeInTheDocument())
  })

  it('an active sample cannot be deleted: the dialog explains and offers Archive instead', async () => {
    vi.mocked(workSamplesApi.list).mockResolvedValue([sample(6, { title: 'Live task', status: 'ACTIVE', assignmentCount: 2 })])
    vi.mocked(workSamplesApi.setStatus).mockImplementation(async (_job, id, status) => sample(id, { title: 'Live task', status, assignmentCount: 2 }))
    renderApp(<WorkSamplesTab job={job()} />, { role: 'HR_MANAGER' })
    const menu = await openSampleMenu('Live task')
    // Assigned: it can no longer go back to draft.
    expect(within(menu).queryByRole('menuitem', { name: 'Move back to draft' })).not.toBeInTheDocument()
    fireEvent.click(within(menu).getByRole('menuitem', { name: 'Delete…' }))
    const dialog = await screen.findByRole('dialog', { name: '“Live task” cannot be deleted' })
    expect(within(dialog).getByText(/Only drafts that were never assigned can be deleted/)).toBeInTheDocument()
    expect(within(dialog).getByText(/It has 2 assignments/)).toBeInTheDocument()
    fireEvent.click(within(dialog).getByRole('button', { name: 'Archive instead' }))
    await waitFor(() => expect(workSamplesApi.setStatus).toHaveBeenCalledWith(101, 6, 'ARCHIVED'))
    expect(workSamplesApi.remove).not.toHaveBeenCalled()
    expect(await screen.findByText('“Live task” is archived')).toBeInTheDocument()
  })

  it('an active sample nobody was assigned can move back to draft', async () => {
    vi.mocked(workSamplesApi.list).mockResolvedValue([sample(7, { title: 'Unused task', status: 'ACTIVE', assignmentCount: 0 })])
    vi.mocked(workSamplesApi.setStatus).mockImplementation(async (_job, id, status) => sample(id, { title: 'Unused task', status }))
    renderApp(<WorkSamplesTab job={job()} />, { role: 'HR_MANAGER' })
    const menu = await openSampleMenu('Unused task')
    fireEvent.click(within(menu).getByRole('menuitem', { name: 'Move back to draft' }))
    const dialog = await screen.findByRole('dialog', { name: 'Move “Unused task” back to draft?' })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Move to draft' }))
    await waitFor(() => expect(workSamplesApi.setStatus).toHaveBeenCalledWith(101, 7, 'DRAFT'))
    expect(await screen.findByText('“Unused task” is a draft again')).toBeInTheDocument()
  })

  it('duplicates a sample as a new draft', async () => {
    vi.mocked(workSamplesApi.list).mockResolvedValue([sample(8, { title: 'Live task', status: 'ACTIVE', assignmentCount: 1 })])
    vi.mocked(workSamplesApi.duplicate).mockResolvedValue(sample(9, { title: 'Live task (copy)' }))
    renderApp(<WorkSamplesTab job={job()} />, { role: 'HR_MANAGER' })
    const menu = await openSampleMenu('Live task')
    fireEvent.click(within(menu).getByRole('menuitem', { name: 'Duplicate as draft' }))
    await waitFor(() => expect(workSamplesApi.duplicate).toHaveBeenCalledWith(101, 8))
    expect(await screen.findByText('Copied as “Live task (copy)”')).toBeInTheDocument()
    expect(await screen.findByRole('button', { name: 'More actions for Live task (copy)' })).toBeInTheDocument()
  })

  it('writes a sample by hand; Cancel with edits asks before discarding', async () => {
    vi.mocked(workSamplesApi.list).mockResolvedValue([])
    renderApp(<WorkSamplesTab job={job()} />, { role: 'HR_MANAGER' })
    fireEvent.click(await screen.findByRole('button', { name: 'Write one yourself' }))
    const drawer = await screen.findByRole('dialog', { name: 'New work sample' })
    fireEvent.change(within(drawer).getByRole('textbox', { name: 'Title' }), { target: { value: 'Rate limiter' } })
    fireEvent.click(within(drawer).getByRole('button', { name: 'Cancel' }))
    const confirm = await screen.findByRole('alertdialog', { name: 'Discard your changes to this work sample?' })
    fireEvent.click(within(confirm).getByRole('button', { name: 'Keep editing' }))
    expect(within(drawer).getByRole('textbox', { name: 'Title' })).toHaveValue('Rate limiter')
    expect(workSamplesApi.create).not.toHaveBeenCalled()
  })

  it('recruiters see no management actions', async () => {
    vi.mocked(workSamplesApi.list).mockResolvedValue([sample(5, { title: 'Draft task' })])
    renderApp(<WorkSamplesTab job={job()} />, { role: 'RECRUITER' })
    expect(await screen.findByText('Draft task')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'More actions for Draft task' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Write one yourself' })).not.toBeInTheDocument()
  })
})
