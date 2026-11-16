import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { CandidateWorkSample } from '../../api/types'
import { fail, file, mockApi, ok, refreshRoute, renderPage } from '../../test/utils'
import { WorkSampleResponsePage } from '../WorkSampleResponsePage'

const LIST = '/candidates/me/work-samples'

function sample(extra: Partial<CandidateWorkSample> = {}): CandidateWorkSample {
  const started = new Date(Date.now() - 10 * 60_000).toISOString()
  return {
    assignmentId: 3, jobTitle: 'Backend Engineer', title: 'Refunds API', instructions: 'Design an idempotent refunds endpoint.', timeLimitMinutes: 90,
    status: 'STARTED', dueAt: null, startedAt: started, submittedAt: null, deadlineAt: new Date(Date.now() + 80 * 60_000).toISOString(), files: [],
    cancelledAt: null, cancelReason: null, ...extra,
  }
}

function renderSample() {
  return renderPage(<WorkSampleResponsePage />, { route: '/work-samples/3', path: '/work-samples/:assignmentId' })
}

describe('WorkSampleResponsePage', () => {
  it('submits text, a link and files as multipart after a confirmation', async () => {
    let current = sample()
    const api = mockApi([
      refreshRoute(),
      { path: LIST, reply: () => ok([current]) },
      {
        method: 'POST',
        path: `${LIST}/3/submit`,
        reply: () => {
          current = sample({ status: 'SUBMITTED', submittedAt: new Date().toISOString(), files: [{ id: 1, originalName: 'design.pdf', contentType: 'application/pdf', sizeBytes: 2048, sha256: 'ab', uploadedAt: '' }] })
          return ok(current, 'Work sample submitted')
        },
      },
    ])
    const { user } = renderSample()
    expect(await screen.findByText('Design an idempotent refunds endpoint.')).toBeInTheDocument()
    await user.type(screen.getByLabelText(/^Response/), 'My answer')
    await user.type(screen.getByLabelText(/Link to your work/), 'https://docs.example/answer')
    const input = document.querySelector<HTMLInputElement>('input[type="file"]')!
    fireEvent.change(input, { target: { files: [file('design.pdf', 2048), file('diagram.png', 4096, 'image/png')] } })
    expect(await screen.findByText('design.pdf')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Submit response' }))
    const confirm = await screen.findByRole('dialog', { name: 'Submit your response?' })
    expect(within(confirm).getByText(/2 files will be attached/)).toBeInTheDocument()
    await user.click(within(confirm).getByRole('button', { name: 'Submit response' }))

    expect(await screen.findByRole('heading', { level: 1, name: 'Refunds API' })).toBeInTheDocument()
    expect(await screen.findByText(/^Submitted \w/)).toBeInTheDocument()
    const form = api.find('POST', `${LIST}/3/submit`)[0].body as FormData
    expect(form.get('responseText')).toBe('My answer')
    expect(form.get('responseUrl')).toBe('https://docs.example/answer')
    expect(form.getAll('files').map(f => (f as File).name)).toEqual(['design.pdf', 'diagram.png'])
    // The local draft is gone after a successful submission.
    await waitFor(() => expect(window.localStorage.getItem('draft-work-sample-3')).toBeNull())
  })

  it('allows files without text, and reports files beyond the third instead of dropping them silently', async () => {
    mockApi([refreshRoute(), { path: LIST, reply: ok([sample()]) }])
    const { user } = renderSample()
    await screen.findByText('Design an idempotent refunds endpoint.')
    const input = document.querySelector<HTMLInputElement>('input[type="file"]')!
    fireEvent.change(input, { target: { files: [file('1.pdf'), file('2.pdf'), file('3.pdf'), file('4.pdf')] } })
    expect(await screen.findByText(/You can attach at most 3 files, so 1 file was not added/)).toBeInTheDocument()
    expect(screen.getByText('3.pdf')).toBeInTheDocument()
    expect(screen.queryByText('4.pdf')).not.toBeInTheDocument()
    // Removing a file clears the message about the set.
    await user.click(screen.getByRole('button', { name: 'Remove 1.pdf' }))
    await waitFor(() => expect(screen.queryByText(/You can attach at most 3 files/)).not.toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: 'Submit response' }))
    expect(await screen.findByRole('dialog', { name: 'Submit your response?' })).toBeInTheDocument()
  })

  it('asks for text or a file, and checks the link', async () => {
    const api = mockApi([refreshRoute(), { path: LIST, reply: ok([sample()]) }])
    const { user } = renderSample()
    await screen.findByText('Design an idempotent refunds endpoint.')
    await user.type(screen.getByLabelText(/Link to your work/), 'ftp://x')
    await user.click(screen.getByRole('button', { name: 'Submit response' }))
    expect(await screen.findByText('Write your response or attach a file before submitting.')).toBeInTheDocument()
    expect(screen.getByText('Enter a full link starting with https:// or http://.')).toBeInTheDocument()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(api.find('POST', `${LIST}/3/submit`)).toHaveLength(0)
  })

  it('accepts responses up to the server limit of 50,000 characters', async () => {
    mockApi([refreshRoute(), { path: LIST, reply: ok([sample()]) }])
    renderSample()
    await screen.findByText('Design an idempotent refunds endpoint.')
    expect(screen.getByLabelText(/^Response/)).toHaveAttribute('maxLength', '50000')
  })

  it('shows the refusal inside the confirmation when the window closed', async () => {
    mockApi([
      refreshRoute(),
      { path: LIST, reply: ok([sample()]) },
      { method: 'POST', path: `${LIST}/3/submit`, reply: fail(400, 'The time limit for this work sample (plus a 5-minute grace period) has passed; the submission was not accepted') },
    ])
    const { user } = renderSample()
    await screen.findByText('Design an idempotent refunds endpoint.')
    await user.type(screen.getByLabelText(/^Response/), 'Late')
    await user.click(screen.getByRole('button', { name: 'Submit response' }))
    const confirm = await screen.findByRole('dialog', { name: 'Submit your response?' })
    await user.click(within(confirm).getByRole('button', { name: 'Submit response' }))
    expect(await within(confirm).findByText(/the submission was not accepted/)).toBeInTheDocument()
  })
})
