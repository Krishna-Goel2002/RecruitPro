import { Link } from 'react-router-dom'
import { act, fireEvent, screen, waitFor } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { queryKeys } from '../../api/queryKeys'
import type { CandidateProfile, JobPublic } from '../../api/types'
import { fail, mockApi, ok, refreshRoute, renderPage } from '../../test/utils'
import { HomePage } from '../HomePage'
import { JobsPage } from '../JobsPage'
import { ProfilePage } from '../ProfilePage'
import { VerificationPage } from '../VerificationPage'

const page = <T,>(content: T[], total = content.length) => ({ content, page: 0, size: 10, totalElements: total, totalPages: Math.ceil(total / 10), first: true, last: true })
const job = (id: number, title: string): JobPublic => ({
  id, organizationName: 'Acme', title, description: 'x', department: 'Engineering', location: 'Pune', jobType: 'FULL_TIME', experienceLevel: 'MID',
  minimumSalary: null, maximumSalary: null, vacancies: 1, deadline: null, publishedAt: null, requirements: [],
})

describe('JobsPage', () => {
  it('keeps the search box in step with the URL (Back, or the nav link)', async () => {
    const api = mockApi([
      refreshRoute(),
      { path: '/jobs', reply: call => ok(page(call.url.searchParams.get('search') ? [job(1, 'Java Developer')] : [job(1, 'Java Developer'), job(2, 'Designer')])) },
      { path: '/candidates/me/applications', reply: ok(page([])) },
    ])
    const { user } = renderPage(
      <>
        <Link to="/jobs">Find jobs</Link>
        <JobsPage />
      </>,
      { route: '/jobs?search=java', path: '/jobs' },
    )
    const search = await screen.findByLabelText('Job title')
    expect(search).toHaveValue('java')
    expect(await screen.findByText('Java Developer')).toBeInTheDocument()
    expect(api.find('GET', '/jobs')[0].url.searchParams.get('search')).toBe('java')

    await user.click(screen.getByRole('link', { name: 'Find jobs' }))
    await waitFor(() => expect(screen.getByLabelText('Job title')).toHaveValue(''))
    expect(await screen.findByText('Designer')).toBeInTheDocument()
    expect(screen.queryByText('Updating…')).not.toBeInTheDocument()
  })

  it('debounces typing into the URL and trims it', async () => {
    mockApi([refreshRoute(), { path: '/jobs', reply: ok(page([])) }, { path: '/candidates/me/applications', reply: ok(page([])) }])
    const { user } = renderPage(<JobsPage />, { route: '/jobs', path: '/jobs' })
    await user.type(await screen.findByLabelText('Job title'), 'data ')
    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent('/jobs?search=data'))
    expect(screen.getByLabelText('Job title')).toHaveValue('data ')
    await waitFor(() => expect(screen.queryByText('Updating…')).not.toBeInTheDocument())
  })

  it('offers the first page for a ?page= past the end', async () => {
    mockApi([refreshRoute(), { path: '/jobs', reply: ok({ ...page([], 12), page: 5 }) }, { path: '/candidates/me/applications', reply: ok(page([])) }])
    renderPage(<JobsPage />, { route: '/jobs?page=5', path: '/jobs' })
    expect(await screen.findByText('This page of results is empty')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Go to the first page' }))
    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent(/^\/jobs$/))
  })

  it('sends part of a department as typed (the server matches "contains") and says so when nothing matches', async () => {
    const api = mockApi([refreshRoute(), { path: '/jobs', reply: ok(page([])) }, { path: '/candidates/me/applications', reply: ok(page([])) }])
    renderPage(<JobsPage />, { route: '/jobs?department=engin', path: '/jobs' })
    expect(await screen.findByText(/Try part of a job title or department name/)).toBeInTheDocument()
    expect(api.calls.some(call => call.path === '/jobs' && call.url.searchParams.get('department') === 'engin')).toBe(true)
  })
})

describe('HomePage', () => {
  it('shows an error with Try again instead of a new-account checklist when a load fails', async () => {
    mockApi([
      refreshRoute(),
      { path: '/candidates/me/resumes', reply: fail(500, 'An unexpected error occurred. Please try again later.') },
      { path: '/candidates/me/verification/sources', reply: ok([]) },
      { path: '/candidates/me/verification/ledger', reply: ok([]) },
      { path: '/candidates/me/applications', reply: ok(page([])) },
      { path: '/candidates/me/work-samples', reply: ok([]) },
      { path: '/candidates/me/interviews', reply: ok([]) },
    ])
    renderPage(<HomePage />, { route: '/home', path: '/home' })
    expect(await screen.findByText('Your overview could not be loaded')).toBeInTheDocument()
    expect(screen.queryByText('Get set up')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument()
  })
})

describe('VerificationPage', () => {
  it('does not show the first-visit "How it works" when the sources failed to load', async () => {
    mockApi([
      refreshRoute(),
      { path: '/candidates/me/verification/ledger', reply: ok([]) },
      { path: '/candidates/me/verification/sources', reply: fail(500, 'An unexpected error occurred. Please try again later.') },
      { path: '/candidates/me/verification/references', reply: ok([]) },
    ])
    renderPage(<VerificationPage />, { route: '/verification', path: '/verification' })
    expect(await screen.findByText('Your sources could not be loaded')).toBeInTheDocument()
    expect(screen.queryByText('How it works')).not.toBeInTheDocument()
  })

  it('ignores a review id that is not a number', async () => {
    const api = mockApi([
      refreshRoute(),
      { path: '/candidates/me/verification/ledger', reply: ok([]) },
      { path: '/candidates/me/verification/sources', reply: ok([]) },
      { path: '/candidates/me/verification/references', reply: ok([]) },
    ])
    renderPage(<VerificationPage />, { route: '/verification?review=abc&add=source&step=connect&kind=FOO', path: '/verification' })
    expect(await screen.findByRole('dialog', { name: 'Add a source' })).toBeInTheDocument()
    expect(api.calls.some(c => c.path.includes('/verdicts/'))).toBe(false)
  })
})

describe('ProfilePage', () => {
  const profile: CandidateProfile = {
    id: 1, email: 'meera@example.com', firstName: 'Meera', lastName: 'Iyer', address: null, city: 'Pune', state: null, country: 'India', zipCode: null,
    linkedinUrl: null, portfolioUrl: null, yearsOfExperience: 5, currentCompany: null, currentDesignation: null, expectedSalary: null,
    noticePeriodDays: null, profileSummary: null, createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z',
  }

  it('keeps unsaved edits when the profile is refetched, and saves the full profile', async () => {
    const api = mockApi([refreshRoute(), { path: '/candidates/me', reply: ok(profile) }, { method: 'PUT', path: '/candidates/me', reply: call => ok({ ...profile, ...(call.body as object) }) }])
    const { user, queryClient } = renderPage(<ProfilePage />, { route: '/profile', path: '/profile' })
    const city = await screen.findByLabelText(/^City/)
    await user.clear(city)
    await user.type(city, 'Mumbai')
    // A background refetch (reconnect, another tab) arrives while editing.
    act(() => queryClient.setQueryData(queryKeys.profile, { ...profile, updatedAt: '2026-11-16T10:00:00Z' }))
    expect(screen.getByLabelText(/^City/)).toHaveValue('Mumbai')
    expect(screen.getByText('You have unsaved changes')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Save changes' }))
    await waitFor(() => expect(api.find('PUT', '/candidates/me')).toHaveLength(1))
    const body = api.find('PUT', '/candidates/me')[0].body as Record<string, unknown>
    expect(body).toMatchObject({ city: 'Mumbai', country: 'India', yearsOfExperience: 5, linkedinUrl: null })
    expect(Object.keys(body)).toHaveLength(13)
  })

  it('does not send anything for Ctrl+S without changes, and checks the salary format', async () => {
    const api = mockApi([refreshRoute(), { path: '/candidates/me', reply: ok(profile) }])
    const { user } = renderPage(<ProfilePage />, { route: '/profile', path: '/profile' })
    await screen.findByLabelText(/^City/)
    await user.keyboard('{Control>}s{/Control}')
    expect(api.find('PUT', '/candidates/me')).toHaveLength(0)
    await user.type(screen.getByLabelText(/Expected annual salary/), '1200000.555')
    await user.keyboard('{Control>}s{/Control}')
    expect(await screen.findByText('Enter an amount in rupees, with at most 2 decimal places.')).toBeInTheDocument()
    expect(api.find('PUT', '/candidates/me')).toHaveLength(0)
  })
})
