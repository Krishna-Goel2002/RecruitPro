import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ProvenanceLine, provenanceHref } from '../design/components'

describe('ProvenanceLine (design system, same file in both portals)', () => {
  it('links only http(s) sources', () => {
    const { rerender } = render(<ProvenanceLine source="GitHub" href="https://github.com/demo/repo" />)
    expect(screen.getByRole('link', { name: /View source/ })).toHaveAttribute('href', 'https://github.com/demo/repo')
    for (const bad of ['javascript:alert(1)', ' JAVASCRIPT:alert(1)', 'data:text/html,<b>x</b>', '/relative/path', '//evil.example/x', 'not a url']) {
      rerender(<ProvenanceLine source="GitHub" href={bad} />)
      expect(screen.queryByRole('link')).not.toBeInTheDocument()
      expect(screen.getByText('GitHub')).toBeInTheDocument()
    }
  })

  it('provenanceHref keeps http and https only', () => {
    expect(provenanceHref('http://a.example/x')).toBe('http://a.example/x')
    expect(provenanceHref('vbscript:x')).toBeUndefined()
    expect(provenanceHref(null)).toBeUndefined()
  })
})
