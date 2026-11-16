import { act, renderHook, screen, waitFor } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { Resume, ResumeAnalysis } from '../../api/types'
import { useDraft } from '../../lib/drafts'
import { fail, mockApi, ok, refreshRoute, renderPage } from '../../test/utils'
import { ResumesPage } from '../ResumesPage'

const resume: Resume = { id: 5, originalFilename: 'meera-cv.pdf', contentType: 'application/pdf', fileSize: 120_000, uploadedAt: '2026-11-15T10:00:00Z' }
const analysis = (extra: Partial<ResumeAnalysis> = {}): ResumeAnalysis => ({
  resumeId: 5, status: 'COMPLETED', parserVersion: '2', errorCode: null, attemptCount: 1, analyzedAt: '2026-11-15T10:01:00Z', flags: [],
  facts: [{ id: 51, type: 'SKILL', value: 'Postgres', extractedValue: 'Postgres', evidence: 'Tuned Postgres queries', sourceSection: 'SKILLS', tier: 'RESUME_STATED', claimedValue: null, claimId: null }],
  ...extra,
})

describe('Resume: suggest a correction', () => {
  it('returns focus to "Suggest a correction" after Escape and after saving, and saves the claim', async () => {
    const api = mockApi([
      refreshRoute(),
      { path: '/candidates/me/resumes', reply: ok([resume]) },
      { path: '/candidates/me/resumes/5/analysis', reply: ok(analysis()) },
      { method: 'PATCH', path: '/candidates/me/resumes/5/analysis/facts/51', reply: ok({ id: 51, type: 'SKILL', value: 'PostgreSQL', extractedValue: 'Postgres' }) },
    ])
    const { user } = renderPage(<ResumesPage />, { route: '/resumes', path: '/resumes' })
    const edit = await screen.findByRole('button', { name: 'Suggest a correction for Postgres' })
    await user.click(edit)
    const field = await screen.findByLabelText('Your correction')
    expect(field).toHaveFocus()
    await user.keyboard('{Escape}')
    await waitFor(() => expect(screen.getByRole('button', { name: 'Suggest a correction for Postgres' })).toHaveFocus())

    await user.click(screen.getByRole('button', { name: 'Suggest a correction for Postgres' }))
    const again = await screen.findByLabelText('Your correction')
    await user.clear(again)
    await user.type(again, 'PostgreSQL{Enter}')
    await waitFor(() => expect(screen.getByRole('button', { name: 'Suggest a correction for Postgres' })).toHaveFocus())
    expect(api.find('PATCH', '/candidates/me/resumes/5/analysis/facts/51')).toHaveLength(1)
    expect(api.find('PATCH', '/candidates/me/resumes/5/analysis/facts/51')[0].body).toEqual({ value: 'PostgreSQL' })
    expect(await screen.findByText('Saved as your claim')).toBeInTheDocument()
  })

  it('says "Correction removed" when the candidate goes back to the text read from the resume', async () => {
    mockApi([
      refreshRoute(),
      { path: '/candidates/me/resumes', reply: ok([resume]) },
      {
        path: '/candidates/me/resumes/5/analysis',
        reply: ok(analysis({ facts: [{ id: 51, type: 'SKILL', value: 'PostgreSQL', extractedValue: 'Postgres', evidence: null, sourceSection: null, tier: 'RESUME_STATED', claimedValue: 'PostgreSQL', claimId: 9 }] })),
      },
      { method: 'PATCH', path: '/candidates/me/resumes/5/analysis/facts/51', reply: ok({ id: 51, type: 'SKILL', value: 'Postgres', extractedValue: 'Postgres' }) },
    ])
    const { user } = renderPage(<ResumesPage />, { route: '/resumes', path: '/resumes' })
    await user.click(await screen.findByRole('button', { name: 'Suggest a correction for Postgres' }))
    const field = await screen.findByLabelText('Your correction')
    await user.clear(field)
    await user.type(field, 'Postgres')
    await user.click(screen.getByRole('button', { name: 'Save as my claim' }))
    expect(await screen.findByText('Correction removed')).toBeInTheDocument()
  })

  it('shows Retry analysis for a failed analysis and sends PATCH .../retry', async () => {
    const api = mockApi([
      refreshRoute(),
      { path: '/candidates/me/resumes', reply: ok([resume]) },
      { path: '/candidates/me/resumes/5/analysis', reply: ok(analysis({ status: 'FAILED', errorCode: 'ENCRYPTED_PDF', facts: [] })) },
      { method: 'PATCH', path: '/candidates/me/resumes/5/analysis/retry', reply: ok(undefined, 'Resume analysis queued for retry') },
    ])
    const { user } = renderPage(<ResumesPage />, { route: '/resumes', path: '/resumes' })
    expect(await screen.findByText(/password-protected/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Retry analysis' }))
    await waitFor(() => expect(api.find('PATCH', '/candidates/me/resumes/5/analysis/retry')).toHaveLength(1))
  })
})

describe('Resume: delete (R2-U9)', () => {
  it('asks first, sends DELETE, and removes the file from the list', async () => {
    let deleted = false
    const api = mockApi([
      refreshRoute(),
      { path: '/candidates/me/resumes', reply: () => ok(deleted ? [] : [resume]) },
      { path: '/candidates/me/resumes/5/analysis', reply: ok(analysis()) },
      { method: 'DELETE', path: '/candidates/me/resumes/5', reply: () => { deleted = true; return ok(null) } },
    ])
    const { user } = renderPage(<ResumesPage />, { route: '/resumes', path: '/resumes' })
    await user.click(await screen.findByRole('button', { name: 'Delete meera-cv.pdf' }))
    expect(await screen.findByRole('alertdialog', { name: 'Delete this resume?' })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Delete resume' }))
    await waitFor(() => expect(api.find('DELETE', '/candidates/me/resumes/5')).toHaveLength(1))
    expect(await screen.findByText('No resume yet')).toBeInTheDocument()
    expect(screen.getByText('meera-cv.pdf deleted')).toBeInTheDocument()
  })

  it('explains a 409 (an application uses the file) and keeps it', async () => {
    mockApi([
      refreshRoute(),
      { path: '/candidates/me/resumes', reply: ok([resume]) },
      { path: '/candidates/me/resumes/5/analysis', reply: ok(analysis()) },
      { method: 'DELETE', path: '/candidates/me/resumes/5', reply: fail(409, 'This resume is used by an application and cannot be deleted') },
    ])
    const { user } = renderPage(<ResumesPage />, { route: '/resumes', path: '/resumes' })
    await user.click(await screen.findByRole('button', { name: 'Delete meera-cv.pdf' }))
    await user.click(await screen.findByRole('button', { name: 'Delete resume' }))
    expect(await screen.findByText(/An application uses this file, so it is kept/)).toBeInTheDocument()
    expect(screen.getAllByText('meera-cv.pdf').length).toBeGreaterThan(0)
  })
})

describe('useDraft', () => {
  it('restores, autosaves after the delay, and flushes pending text when the key changes', async () => {
    window.localStorage.setItem('draft-a', 'restored text')
    const { result, rerender } = renderHook(({ k }) => useDraft(k, 800), { initialProps: { k: 'draft-a' as string | null } })
    expect(result.current.value).toBe('restored text')
    expect(result.current.restored).toBe(true)
    act(() => result.current.setValue('typed just before closing'))
    // Closed within the debounce: the text must not be lost.
    rerender({ k: null })
    expect(window.localStorage.getItem('draft-a')).toBe('typed just before closing')
    rerender({ k: 'draft-a' })
    expect(result.current.value).toBe('typed just before closing')
    act(() => result.current.clear())
    rerender({ k: null })
    expect(window.localStorage.getItem('draft-a')).toBeNull()
  })
})
