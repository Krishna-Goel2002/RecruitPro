import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { Application, JobPublic, Resume } from '../../api/types'
import { fail, file, mockApi, ok, refreshRoute, renderPage } from '../../test/utils'
import { ApplyDialog } from '../jobs/ApplyDialog'

const job: JobPublic = {
  id: 101,
  organizationName: 'Acme Payments',
  title: 'Backend Engineer',
  description: 'Build payments.',
  department: 'Engineering',
  location: 'Pune',
  jobType: 'FULL_TIME',
  experienceLevel: 'MID',
  minimumSalary: null,
  maximumSalary: null,
  vacancies: 1,
  deadline: null,
  publishedAt: null,
  requirements: [],
}
const older: Resume = { id: 1, originalFilename: 'cv-2025.pdf', contentType: 'application/pdf', fileSize: 1000, uploadedAt: '2025-01-01T00:00:00Z' }
const newer: Resume = { id: 2, originalFilename: 'cv-2026.pdf', contentType: 'application/pdf', fileSize: 2000, uploadedAt: '2026-11-01T00:00:00Z' }
const application: Application = {
  id: 3001, jobId: 101, jobTitle: 'Backend Engineer', organizationName: 'Acme Payments', resumeId: 2, resumeFilename: 'cv-2026.pdf',
  status: 'SUBMITTED', coverLetter: null, appliedAt: '2026-11-16T10:00:00Z', updatedAt: '2026-11-16T10:00:00Z', statusChangedAt: null,
}
const page = (content: unknown[]) => ({ content, page: 0, size: 100, totalElements: content.length, totalPages: 1, first: true, last: true })

function renderDialog(onClose = vi.fn()) {
  return { onClose, ...renderPage(<ApplyDialog job={job} matches={[]} open onClose={onClose} />) }
}

describe('ApplyDialog', () => {
  it('preselects the newest resume, reviews, submits once and clears the cover-letter draft', async () => {
    const api = mockApi([
      refreshRoute(),
      { path: '/candidates/me/resumes', reply: ok([older, newer]) },
      { path: '/candidates/me/verification/ledger', reply: ok([]) },
      { method: 'POST', path: '/jobs/101/applications', reply: ok(application, 'Application submitted') },
    ])
    const { user } = renderDialog()
    const dialog = await screen.findByRole('dialog', { name: 'Apply: Backend Engineer' })
    expect(await within(dialog).findByRole('radio', { name: /cv-2026\.pdf/ })).toBeChecked()

    await user.type(within(dialog).getByLabelText(/Cover letter/), 'I built refunds at scale.')
    await waitFor(() => expect(window.localStorage.getItem('draft-cover-letter-101')).toBe('I built refunds at scale.'))

    await user.click(within(dialog).getByRole('button', { name: 'Review application' }))
    expect(within(dialog).getByText('cv-2026.pdf')).toBeInTheDocument()
    expect(within(dialog).getByText('Included (25 characters)')).toBeInTheDocument()
    // Focus follows the step for keyboard and screen-reader users.
    await waitFor(() => expect(within(dialog).getByRole('generic', { name: 'Check before you submit' })).toHaveFocus())

    const submit = within(dialog).getByRole('button', { name: 'Submit application' })
    await user.dblClick(submit)
    expect(await screen.findByRole('dialog', { name: 'Application sent' })).toBeInTheDocument()
    expect(api.find('POST', '/jobs/101/applications')).toHaveLength(1)
    expect(api.find('POST', '/jobs/101/applications')[0].body).toEqual({ resumeId: 2, coverLetter: 'I built refunds at scale.' })
    expect(screen.getByRole('link', { name: 'View application' })).toHaveAttribute('href', '/applications/3001')
    await waitFor(() => expect(window.localStorage.getItem('draft-cover-letter-101')).toBeNull())
  })

  it('returns focus to the resume step when going back', async () => {
    mockApi([refreshRoute(), { path: '/candidates/me/resumes', reply: ok([newer]) }, { path: '/candidates/me/verification/ledger', reply: ok([]) }])
    const { user } = renderDialog()
    const dialog = await screen.findByRole('dialog')
    await within(dialog).findByRole('radio', { name: /cv-2026\.pdf/ })
    await user.click(within(dialog).getByRole('button', { name: 'Review application' }))
    await user.click(within(dialog).getByRole('button', { name: 'Back' }))
    await waitFor(() => expect(within(dialog).getByRole('group', { name: 'Choose a resume' })).toHaveFocus())
  })

  it('turns a 409 into "You already applied" with a link to that application', async () => {
    mockApi([
      refreshRoute(),
      { path: '/candidates/me/resumes', reply: ok([newer]) },
      { path: '/candidates/me/verification/ledger', reply: ok([]) },
      { method: 'POST', path: '/jobs/101/applications', reply: fail(409, 'You have already applied to this job') },
      { path: '/candidates/me/applications', reply: ok(page([application])) },
    ])
    const { user } = renderDialog()
    const dialog = await screen.findByRole('dialog')
    await within(dialog).findByRole('radio', { name: /cv-2026\.pdf/ })
    await user.click(within(dialog).getByRole('button', { name: 'Review application' }))
    await user.click(within(dialog).getByRole('button', { name: 'Submit application' }))
    expect(await within(dialog).findByText('You already applied to this role')).toBeInTheDocument()
    expect(within(dialog).getByRole('link', { name: 'Open it' })).toHaveAttribute('href', '/applications/3001')
    expect(within(dialog).getByRole('button', { name: 'Submit application' })).toBeDisabled()
  })

  it('uploads a resume inside the dialog when there is none, and selects it', async () => {
    const api = mockApi([
      refreshRoute(),
      { path: '/candidates/me/resumes', reply: ok([]), times: 1 },
      { path: '/candidates/me/resumes', reply: ok([newer]) },
      { path: '/candidates/me/verification/ledger', reply: ok([]) },
      { method: 'POST', path: '/candidates/me/resumes', reply: ok(newer, 'Resume uploaded') },
      { method: 'POST', path: '/jobs/101/applications', reply: ok(application) },
    ])
    const { user } = renderDialog()
    const dialog = await screen.findByRole('dialog')
    expect(await within(dialog).findByText('Add your resume')).toBeInTheDocument()
    expect(within(dialog).getByRole('button', { name: 'Review application' })).toBeDisabled()
    const input = dialog.querySelector<HTMLInputElement>('input[type="file"]')!
    await user.upload(input, file('cv-2026.pdf', 2000))
    expect(await within(dialog).findByText('cv-2026.pdf uploaded and selected.')).toBeInTheDocument()
    const upload = api.find('POST', '/candidates/me/resumes')[0]
    expect((upload.body as FormData).get('file')).toBeInstanceOf(File)
    await user.click(within(dialog).getByRole('button', { name: 'Review application' }))
    await user.click(within(dialog).getByRole('button', { name: 'Submit application' }))
    await screen.findByRole('dialog', { name: 'Application sent' })
    expect(api.find('POST', '/jobs/101/applications')[0].body).toEqual({ resumeId: 2, coverLetter: null })
  })

  it('refuses a file that is not a resume type before uploading', async () => {
    const api = mockApi([refreshRoute(), { path: '/candidates/me/resumes', reply: ok([]) }, { path: '/candidates/me/verification/ledger', reply: ok([]) }])
    renderPage(<ApplyDialog job={job} matches={[]} open onClose={() => undefined} />)
    const dialog = await screen.findByRole('dialog')
    await within(dialog).findByText('Add your resume')
    const input = dialog.querySelector<HTMLInputElement>('input[type="file"]')!
    // A drop ignores the picker's accept filter, so the dropzone's own check is what refuses it.
    fireEvent.change(input, { target: { files: [file('cv.exe', 10, 'application/octet-stream')] } })
    expect(await within(dialog).findByText('cv.exe is not an accepted file type.')).toBeInTheDocument()
    fireEvent.change(input, { target: { files: [file('huge.pdf', 11 * 1024 * 1024)] } })
    expect(await within(dialog).findByText('huge.pdf is larger than 10.0 MB.')).toBeInTheDocument()
    expect(api.find('POST', '/candidates/me/resumes')).toHaveLength(0)
  })
})
