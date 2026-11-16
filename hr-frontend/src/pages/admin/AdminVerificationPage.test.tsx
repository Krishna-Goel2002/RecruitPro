import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { VerificationMetrics } from '../../api/types'
import { page } from '../../test/fixtures'
import { renderApp } from '../../test/render'
import { AdminVerificationPage } from './AdminVerificationPage'

vi.mock('../../api/verification', () => ({
  verificationApi: { settings: vi.fn(), updateSettings: vi.fn(), shadowQueue: vi.fn(), label: vi.fn(), metrics: vi.fn() },
}))
import { verificationApi } from '../../api/verification'

const connectors = { GITHUB: true, GITLAB: true, WEBSITE: true, DOCUMENT: true, OPEN_BADGE: true, REFERENCE: true }
const metrics = (ready: boolean): VerificationMetrics => ({
  labelled: ready ? 180 : 40, agreement: 0.9, falseCorroborationRate: ready ? 0.01 : 0.06, kappa: ready ? 0.8 : 0.4, byVerifier: [], byVerdict: [], readyForVisible: ready,
})

beforeEach(() => {
  vi.mocked(verificationApi.settings).mockResolvedValue({ mode: 'SHADOW', connectors, updatedAt: null, updatedByName: null, trustedEmployerDomains: [], trustedBadgeIssuers: [] })
  vi.mocked(verificationApi.shadowQueue).mockResolvedValue(page([]))
  vi.mocked(verificationApi.updateSettings).mockImplementation(async body => ({ mode: body.mode, connectors, updatedAt: null, updatedByName: 'Admin', trustedEmployerDomains: [], trustedBadgeIssuers: [] }))
})

describe('admin verification: switching to VISIBLE', () => {
  it('not ready: the confirmation names the risk and the button says "anyway"', async () => {
    vi.mocked(verificationApi.metrics).mockResolvedValue(metrics(false))
    renderApp(<AdminVerificationPage />, { role: 'ADMIN', route: '/admin/verification' })
    fireEvent.click(await screen.findByRole('button', { name: 'Switch to visible' }))
    const dialog = await screen.findByRole('alertdialog', { name: /before the targets are met/ })
    expect(within(dialog).getByText(/false corroboration would lift a candidate unfairly/i)).toBeInTheDocument()
    expect(document.activeElement).toBe(within(dialog).getByRole('button', { name: 'Cancel' }))
    fireEvent.click(within(dialog).getByRole('button', { name: 'Switch to visible anyway' }))
    await waitFor(() => expect(verificationApi.updateSettings).toHaveBeenCalledWith({ mode: 'VISIBLE', connectors }))
  })

  it('ready: a plain confirmation', async () => {
    vi.mocked(verificationApi.metrics).mockResolvedValue(metrics(true))
    renderApp(<AdminVerificationPage />, { role: 'ADMIN', route: '/admin/verification' })
    fireEvent.click(await screen.findByRole('button', { name: 'Switch to visible' }))
    const dialog = await screen.findByRole('dialog', { name: 'Switch verification to visible?' })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Switch to visible' }))
    await waitFor(() => expect(verificationApi.updateSettings).toHaveBeenCalled())
  })

  it('cancel sends nothing', async () => {
    vi.mocked(verificationApi.metrics).mockResolvedValue(metrics(false))
    renderApp(<AdminVerificationPage />, { role: 'ADMIN', route: '/admin/verification' })
    fireEvent.click(await screen.findByRole('button', { name: 'Switch to visible' }))
    fireEvent.click(within(await screen.findByRole('alertdialog')).getByRole('button', { name: 'Cancel' }))
    expect(verificationApi.updateSettings).not.toHaveBeenCalled()
  })

  it('HR managers see readiness and the queue, never the mode switch or settings', async () => {
    vi.mocked(verificationApi.metrics).mockResolvedValue(metrics(false))
    renderApp(<AdminVerificationPage />, { role: 'HR_MANAGER', route: '/admin/verification' })
    expect(await screen.findByText('Mode is managed by an administrator')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Switch to visible' })).not.toBeInTheDocument()
    expect(screen.queryByRole('tab', { name: /Connectors/ })).not.toBeInTheDocument()
    expect(verificationApi.settings).not.toHaveBeenCalled()
  })

  it('trusted lists: invalid domains are refused in place; valid ones are normalised and saved', async () => {
    vi.mocked(verificationApi.metrics).mockResolvedValue(metrics(true))
    renderApp(<AdminVerificationPage />, { role: 'ADMIN', route: '/admin/verification?tab=trusted' })
    const input = await screen.findByLabelText('Employer domains')
    fireEvent.change(input, { target: { value: 'not a domain' } })
    fireEvent.keyDown(input, { key: 'Enter' })
    expect(await screen.findByText(/is not a domain name/)).toBeInTheDocument()
    fireEvent.change(input, { target: { value: 'https://www.Globex.example/careers' } })
    fireEvent.keyDown(input, { key: 'Enter' })
    expect(await screen.findByText('globex.example')).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Issuer domains'), { target: { value: 'co.uk' } })
    fireEvent.keyDown(screen.getByLabelText('Issuer domains'), { key: 'Enter' })
    expect(await screen.findByText(/public suffix/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /Save/ }))
    await waitFor(() => expect(vi.mocked(verificationApi.updateSettings).mock.calls[0]?.[0]).toEqual({ mode: 'SHADOW', trustedEmployerDomains: ['globex.example'], trustedBadgeIssuers: [] }))
  })
})
