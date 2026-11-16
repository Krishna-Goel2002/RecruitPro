import { act, fireEvent, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { renderApp } from '../../test/render'
import { AdminAuditPage } from './AdminAuditPage'

vi.mock('../../api/admin', () => ({ adminApi: { audit: vi.fn(), exportAudit: vi.fn() } }))
import { adminApi } from '../../api/admin'

describe('audit CSV export', () => {
  it('warns when the server cut the export at 50,000 rows', async () => {
    vi.mocked(adminApi.audit).mockResolvedValue({ content: [], page: 0, size: 25, totalElements: 0, totalPages: 0 } as never)
    vi.mocked(adminApi.exportAudit).mockResolvedValue({ filename: 'audit.csv', truncated: true })
    renderApp(<AdminAuditPage />, { role: 'ADMIN', route: '/admin/audit' })
    await act(async () => { fireEvent.click(await screen.findByRole('button', { name: 'Export CSV' })) })
    expect(await screen.findByText('Only the first 50,000 rows were exported. Narrow the date range.')).toBeInTheDocument()
  })

  it('confirms a complete export', async () => {
    vi.mocked(adminApi.audit).mockResolvedValue({ content: [], page: 0, size: 25, totalElements: 0, totalPages: 0 } as never)
    vi.mocked(adminApi.exportAudit).mockResolvedValue({ filename: 'audit.csv', truncated: false })
    renderApp(<AdminAuditPage />, { role: 'ADMIN', route: '/admin/audit' })
    await act(async () => { fireEvent.click(await screen.findByRole('button', { name: 'Export CSV' })) })
    expect(await screen.findByText('Exported audit.csv')).toBeInTheDocument()
  })
})
