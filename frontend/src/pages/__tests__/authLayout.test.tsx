import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import brand from '../../design/brand/active'
import { AuthLayout } from '../auth/AuthLayout'

function renderLayout() {
  return render(
    <MemoryRouter initialEntries={['/login']}>
      <Routes>
        <Route element={<AuthLayout />}>
          <Route path="/login" element={<h1>Sign in</h1>} />
        </Route>
      </Routes>
    </MemoryRouter>,
  )
}

describe('signed-out layout', () => {
  it('links back to the landing site (local default)', () => {
    renderLayout()
    expect(screen.getByRole('link', { name: `About ${brand.name}` })).toHaveAttribute('href', 'http://localhost:5170/')
  })

  it('uses VITE_SITE_URL when the build sets it', () => {
    vi.stubEnv('VITE_SITE_URL', 'https://www.example.org')
    renderLayout()
    expect(screen.getByRole('link', { name: `About ${brand.name}` })).toHaveAttribute('href', 'https://www.example.org/')
  })
})
