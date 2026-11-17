import { act, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import App from '../App'
import { portalLinks } from '../site/config'
import { isLandingPath } from '../sections/NotFound'
import { FAQ } from '../sections/FaqSection'

describe('landing page structure', () => {
  it('has the landmarks, a skip link and one h1', () => {
    render(<App />)
    expect(screen.getByRole('banner')).toBeInTheDocument()
    expect(screen.getByRole('main')).toHaveAttribute('id', 'main')
    expect(screen.getByRole('contentinfo')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Skip to content' })).toHaveAttribute('href', '#main')
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1)
  })

  it('renders every section of the plan with a labelled heading', () => {
    render(<App />)
    for (const [id, heading] of [
      ['problem', /list of claims/i],
      ['how', /three visible steps/i],
      ['teams', /shortlist you can defend/i],
      ['candidates', /on your terms/i],
      ['trust', /fair by default/i],
      ['faq', /questions people ask/i],
    ] as const) {
      const section = document.getElementById(id)
      expect(section, id).not.toBeNull()
      expect(within(section!).getByRole('heading', { level: 2, name: heading })).toBeInTheDocument()
    }
    // The product preview says what it shows to assistive tech, and is marked as example data.
    expect(screen.getByText(/Example applicant A-3014/)).toBeInTheDocument()
    expect(screen.getAllByText('Example data').length).toBeGreaterThan(0)
  })

  it('states the coverage caveat in plain words', () => {
    render(<App />)
    expect(screen.getAllByText(/not a probability of success/i).length).toBeGreaterThan(0)
  })
})

describe('calls to action', () => {
  it('point at the portals on the same origin by default (gateway: /hr, /candidate)', () => {
    render(<App />)
    const header = screen.getByRole('banner')
    expect(within(header).getByRole('link', { name: 'HR sign in' })).toHaveAttribute('href', '/hr/login')
    expect(within(header).getByRole('link', { name: 'Candidate sign in' })).toHaveAttribute('href', '/candidate/login')
    const hero = screen.getByRole('heading', { level: 1 }).closest('section')!
    expect(within(hero).getByRole('link', { name: /For companies.*HR sign in/ })).toHaveAttribute('href', '/hr/login')
    expect(within(hero).getByRole('link', { name: /For candidates.*Find jobs/ })).toHaveAttribute('href', '/candidate/jobs')
    for (const a of screen.getAllByRole('link', { name: /HR sign in|Sign in to the HR portal/ })) expect(a).toHaveAttribute('href', '/hr/login')
    expect(screen.getAllByRole('link', { name: 'Create an account' })[0]).toHaveAttribute('href', '/candidate/register')
  })

  it('use VITE_HR_URL and VITE_CANDIDATE_URL from a per-port build', () => {
    vi.stubEnv('VITE_HR_URL', 'https://hr.example.org/')
    vi.stubEnv('VITE_CANDIDATE_URL', 'https://jobs.example.org')
    render(<App />)
    const hrLinks = screen.getAllByRole('link', { name: /HR sign in|HR portal/ })
    expect(hrLinks.length).toBeGreaterThan(1)
    for (const a of hrLinks) expect(a).toHaveAttribute('href', 'https://hr.example.org/login')
    for (const a of screen.getAllByRole('link', { name: /find jobs/i })) expect(a).toHaveAttribute('href', 'https://jobs.example.org/jobs')
    expect(screen.getByRole('link', { name: 'Candidate sign in' })).toHaveAttribute('href', 'https://jobs.example.org/login')
    expect(screen.getAllByRole('link', { name: 'Create an account' })[0]).toHaveAttribute('href', 'https://jobs.example.org/register')
  })

  it('never builds a link from a value that is neither a same-origin path nor an absolute http(s) URL', () => {
    expect(portalLinks({ VITE_HR_URL: 'ftp://nope' }).hrSignIn).toBe('/hr/login')
    expect(portalLinks({ VITE_HR_URL: 'javascript:alert(1)' }).hrSignIn).toBe('/hr/login')
    expect(portalLinks({ VITE_CANDIDATE_URL: '//evil.example' }).findJobs).toBe('/candidate/jobs')
    expect(portalLinks({ VITE_SITE_URL: '  ' }).home).toBe('/')
  })

  it('knows its own base path', () => {
    expect(isLandingPath('/', '/')).toBe(true)
    expect(isLandingPath('/index.html', '/')).toBe(true)
    expect(isLandingPath('/pricing', '/')).toBe(false)
    expect(isLandingPath('/home/', '/home/')).toBe(true)
    expect(isLandingPath('/home', '/home/')).toBe(true)
  })
})

describe('FAQ accordion', () => {
  it('toggles with the mouse and with Enter / Space', async () => {
    const user = userEvent.setup()
    render(<App />)
    const first = screen.getByRole('button', { name: FAQ[0].q })
    const region = document.getElementById(first.getAttribute('aria-controls')!)!
    expect(first).toHaveAttribute('aria-expanded', 'false')
    expect(region).toHaveAttribute('role', 'region')
    expect(region).toHaveAttribute('aria-labelledby', first.id)
    await user.click(first)
    expect(first).toHaveAttribute('aria-expanded', 'true')
    expect(region).toHaveAttribute('data-open')
    first.focus()
    await user.keyboard('{Enter}')
    expect(first).toHaveAttribute('aria-expanded', 'false')
    await user.keyboard(' ')
    expect(first).toHaveAttribute('aria-expanded', 'true')
  })

  it('moves focus with the arrow keys, Home and End (wrapping)', async () => {
    const user = userEvent.setup()
    render(<App />)
    const buttons = FAQ.map(f => screen.getByRole('button', { name: f.q }))
    buttons[0].focus()
    await user.keyboard('{ArrowDown}')
    expect(buttons[1]).toHaveFocus()
    await user.keyboard('{End}')
    expect(buttons[buttons.length - 1]).toHaveFocus()
    await user.keyboard('{ArrowDown}')
    expect(buttons[0]).toHaveFocus()
    await user.keyboard('{ArrowUp}')
    expect(buttons[buttons.length - 1]).toHaveFocus()
    await user.keyboard('{Home}')
    expect(buttons[0]).toHaveFocus()
    // Moving focus does not open anything.
    expect(buttons.every(b => b.getAttribute('aria-expanded') === 'false')).toBe(true)
  })
})

describe('header menu (phones)', () => {
  it('opens and closes with the button, closes on Escape and returns focus, and closes after choosing a link', async () => {
    const user = userEvent.setup()
    render(<App />)
    const button = screen.getByRole('button', { name: 'Open menu' })
    const panel = document.getElementById('site-menu')!
    expect(button).toHaveAttribute('aria-controls', 'site-menu')
    expect(panel).not.toBeVisible()

    await user.click(button)
    expect(button).toHaveAttribute('aria-expanded', 'true')
    expect(panel).toBeVisible()
    expect(within(panel).getByRole('link', { name: 'For companies: HR sign in' })).toHaveAttribute('href', '/hr/login')
    expect(within(panel).getByRole('link', { name: 'For candidates: sign in' })).toHaveAttribute('href', '/candidate/login')

    await user.keyboard('{Escape}')
    expect(panel).not.toBeVisible()
    expect(screen.getByRole('button', { name: 'Open menu' })).toHaveFocus()

    await user.click(screen.getByRole('button', { name: 'Open menu' }))
    await user.click(within(panel).getByRole('link', { name: 'FAQ' }))
    expect(panel).not.toBeVisible()
  })
})

describe('motion', () => {
  it('reduced motion: nothing waits to be revealed and the hero shows its end state', () => {
    // setup.ts answers prefers-reduced-motion: reduce.
    render(<App />)
    // The design-system reveal never hides anything under reduced motion.
    expect(document.querySelectorAll('[data-reveal]')).toHaveLength(0)
    // Bars and ladders are filled at once; the hero shows the end of its story without timers.
    expect(document.querySelectorAll('.lad-fill[data-filled]').length).toBeGreaterThan(0)
    expect(document.querySelector('.rk')).toHaveAttribute('data-state', 'shown')
    expect(screen.getByText('Confirmed verdict · github.com')).toBeInTheDocument()
  })

  it('full motion: sections reveal on scroll and the hero plays its story once', () => {
    vi.useFakeTimers()
    try {
      vi.spyOn(window, 'matchMedia').mockImplementation(
        query =>
          ({
            matches: false,
            media: query,
            onchange: null,
            addEventListener: () => undefined,
            removeEventListener: () => undefined,
            addListener: () => undefined,
            removeListener: () => undefined,
            dispatchEvent: () => false,
          }) as unknown as MediaQueryList,
      )
      const observed: { cb: IntersectionObserverCallback; el: Element }[] = []
      class IO {
        constructor(private cb: IntersectionObserverCallback) {}
        observe(el: Element) {
          observed.push({ cb: this.cb, el })
        }
        disconnect() {}
        unobserve() {}
        takeRecords() {
          return []
        }
      }
      vi.stubGlobal('IntersectionObserver', IO)
      render(<App />)
      expect(document.querySelectorAll('[data-reveal="hidden"]').length).toBeGreaterThan(10)
      expect(document.querySelector('.rk')).toHaveAttribute('data-state', 'pending')
      expect(screen.queryByText('Confirmed verdict · github.com')).not.toBeInTheDocument()

      // Scroll every reveal target into view.
      const rk = document.querySelector('.rk')!
      act(() => {
        for (const { cb, el } of observed) {
          if (el.hasAttribute('data-reveal') || el === rk) cb([{ isIntersecting: true, target: el } as IntersectionObserverEntry], {} as IntersectionObserver)
        }
      })
      act(() => {
        vi.advanceTimersByTime(50) // the reveal flips to "shown" on the next animation frame
      })
      expect(document.querySelectorAll('[data-reveal="hidden"]')).toHaveLength(0)
      expect(document.querySelectorAll('[data-reveal="shown"]').length).toBeGreaterThan(10)
      expect(rk).toHaveAttribute('data-state', 'shown')

      act(() => {
        vi.advanceTimersByTime(2300)
      })
      expect(screen.getByText('Confirmed verdict · github.com')).toBeInTheDocument()
    } finally {
      vi.useRealTimers()
    }
  })
})

describe('links and unknown addresses', () => {
  it('every in-page link points at a section that exists', () => {
    render(<App />)
    const anchors = Array.from(document.querySelectorAll<HTMLAnchorElement>('a[href^="#"]'))
    expect(anchors.length).toBeGreaterThan(5)
    for (const a of anchors) {
      const id = a.getAttribute('href')!.slice(1)
      expect(document.getElementById(id), a.getAttribute('href')!).not.toBeNull()
    }
    // Every other link goes to a portal on this origin (/candidate/..., /hr/...), never javascript: or another host.
    for (const a of Array.from(document.querySelectorAll<HTMLAnchorElement>('a:not([href^="#"])'))) {
      expect(a.getAttribute('href')).toMatch(/^\/(candidate|hr)\/[a-z]/)
    }
  })

  it('shows a not-found page with a way home instead of the landing page at an unknown path', () => {
    window.history.pushState({}, '', '/pricing')
    try {
      render(<App />)
      expect(screen.getByRole('heading', { level: 1, name: 'There is nothing at this address' })).toBeInTheDocument()
      expect(screen.getByRole('link', { name: 'Back to the home page' })).toHaveAttribute('href', '/')
      expect(screen.getByRole('link', { name: /For candidates: find jobs/ })).toHaveAttribute('href', '/candidate/jobs')
      expect(screen.getByRole('link', { name: /For companies: HR sign in/ })).toHaveAttribute('href', '/hr/login')
      // No section links that would point at /pricing#how.
      expect(document.querySelector('a[href^="#"]')).toBeNull()
      expect(document.title).toMatch(/^Page not found/)
    } finally {
      window.history.pushState({}, '', '/')
    }
  })
})
