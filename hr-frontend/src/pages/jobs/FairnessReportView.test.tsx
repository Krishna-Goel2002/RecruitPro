import { fireEvent, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { FairnessReport } from '../../api/types'
import { renderApp } from '../../test/render'
import { JobFairnessTab } from './FairnessReportView'

vi.mock('../../api/review', () => ({ reviewApi: { fairness: vi.fn() } }))
import { reviewApi } from '../../api/review'

const report: FairnessReport = { scope: 'JOB', jobId: 101, minimumGroupSize: 5, surveyResponseRate: 0.5, dimensions: [] } as unknown as FairnessReport

beforeEach(() => { vi.mocked(reviewApi.fairness).mockResolvedValue(report) })

describe('fairness date range', () => {
  it('an invalid range in the URL falls back to all time and says so', async () => {
    renderApp(<JobFairnessTab jobId={101} />, { route: '/jobs/101?tab=fairness&from=2026-02-30&to=2026-03-01' })
    expect(await screen.findByText(/dates in the link were not valid/)).toBeInTheDocument()
    await waitFor(() => expect(reviewApi.fairness).toHaveBeenCalledWith(101, {}, expect.anything()))
  })

  it('from after to is refused before anything is sent', async () => {
    renderApp(<JobFairnessTab jobId={101} />, { route: '/jobs/101?tab=fairness' })
    fireEvent.click(await screen.findByLabelText('Custom'))
    fireEvent.change(screen.getByLabelText('From'), { target: { value: '2026-05-10' } })
    fireEvent.change(screen.getByLabelText('To'), { target: { value: '2026-05-01' } })
    fireEvent.click(screen.getByRole('button', { name: 'Apply' }))
    expect(await screen.findByText(/on or after the start date/)).toBeInTheDocument()
    expect(screen.getByTestId('location').textContent).not.toMatch(/from=/)
  })

  it('a valid custom range goes to the URL and to the request', async () => {
    renderApp(<JobFairnessTab jobId={101} />, { route: '/jobs/101?tab=fairness' })
    fireEvent.click(await screen.findByLabelText('Custom'))
    fireEvent.change(screen.getByLabelText('From'), { target: { value: '2026-05-01' } })
    fireEvent.change(screen.getByLabelText('To'), { target: { value: '2026-05-31' } })
    fireEvent.click(screen.getByRole('button', { name: 'Apply' }))
    await waitFor(() => expect(screen.getByTestId('location').textContent).toMatch(/from=2026-05-01&to=2026-05-31/))
    await waitFor(() => expect(reviewApi.fairness).toHaveBeenCalledWith(101, { from: '2026-05-01', to: '2026-05-31' }, expect.anything()))
  })
})
