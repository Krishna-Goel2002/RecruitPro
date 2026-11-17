import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import { AuthLayout } from '../auth/AuthLayout'

function renderLayout(path = '/login') {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route element={<AuthLayout />}>
          <Route path="/login" element={<h1>Sign in</h1>} />
          <Route path="/register" element={<h1>Create an account</h1>} />
          <Route path="/forgot-password" element={<h1>Reset your password</h1>} />
        </Route>
      </Routes>
    </MemoryRouter>,
  )
}

describe('signed-out layout: connected to the landing site and the HR portal', () => {
  it.each(['/login', '/register', '/forgot-password'])('%s links back home and to the HR sign-in (gateway defaults)', path => {
    renderLayout(path)
    expect(screen.getByRole('link', { name: 'Back to home' })).toHaveAttribute('href', '/')
    expect(screen.getByRole('link', { name: 'Sign in to the HR workspace' })).toHaveAttribute('href', '/hr/login')
    expect(screen.getByText(/Hiring\?/)).toBeInTheDocument()
  })

  it('uses the per-port URLs when the build sets them', () => {
    vi.stubEnv('VITE_SITE_URL', 'http://localhost:5170')
    vi.stubEnv('VITE_HR_URL', 'http://localhost:5174')
    renderLayout()
    expect(screen.getByRole('link', { name: 'Back to home' })).toHaveAttribute('href', 'http://localhost:5170/')
    expect(screen.getByRole('link', { name: 'Sign in to the HR workspace' })).toHaveAttribute('href', 'http://localhost:5174/login')
  })
})
