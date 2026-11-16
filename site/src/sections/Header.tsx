import { useEffect, useRef, useState } from 'react'
import brand from '../design/brand/active'
import { buttonProps, IconButton, ThemeSwitch } from '../design/components'
import { ArrowRight, Icon, Menu, X } from '../design/icons'
import type { PortalLinks } from '../site/config'

export const NAV = [
  { id: 'how', label: 'How it works' },
  { id: 'teams', label: 'For HR teams' },
  { id: 'candidates', label: 'For candidates' },
  { id: 'trust', label: 'Trust and privacy' },
  { id: 'faq', label: 'FAQ' },
] as const

/** Which section is in the middle band of the viewport (for aria-current on the nav). */
function useActiveSection(ids: readonly string[]): string | null {
  const [active, setActive] = useState<string | null>(null)
  useEffect(() => {
    if (typeof IntersectionObserver === 'undefined') return
    const io = new IntersectionObserver(
      entries => {
        for (const e of entries) if (e.isIntersecting) setActive(e.target.id)
      },
      { rootMargin: '-45% 0px -50% 0px' },
    )
    ids.forEach(id => {
      const el = document.getElementById(id)
      if (el) io.observe(el)
    })
    return () => io.disconnect()
  }, [ids])
  return active
}

/**
 * Sticky header: wordmark, section links, theme toggle and the two portal entries. It gains a hairline and a
 * surface once the page scrolls. Below 1060 px the links move into a disclosure panel (Menu button; Escape closes
 * and returns focus; choosing a link closes it).
 */
export function Header({ links }: { links: PortalLinks }) {
  const [scrolled, setScrolled] = useState(false)
  const [menuOpen, setMenuOpen] = useState(false)
  const menuButton = useRef<HTMLButtonElement>(null)
  const active = useActiveSection(NAV.map(n => n.id))

  useEffect(() => {
    const onScroll = () => setScrolled(window.scrollY > 8)
    onScroll()
    window.addEventListener('scroll', onScroll, { passive: true })
    return () => window.removeEventListener('scroll', onScroll)
  }, [])

  useEffect(() => {
    if (!menuOpen) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        setMenuOpen(false)
        menuButton.current?.focus()
      }
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [menuOpen])

  const navLinks = (onPick?: () => void) =>
    NAV.map(n => (
      <li key={n.id}>
        <a href={`#${n.id}`} className="hd-link" aria-current={active === n.id ? 'true' : undefined} onClick={onPick}>
          {n.label}
        </a>
      </li>
    ))

  return (
    <header className="hd" data-scrolled={scrolled || menuOpen || undefined}>
      <div className="hd-bar wrap">
        <a href="#top" className="hd-brand" aria-label={`${brand.name}, back to top`}>
          <brand.Mark size={22} />
          <span className="p-wordmark">{brand.name}</span>
        </a>
        <nav className="hd-nav" aria-label="Sections">
          <ul>{navLinks()}</ul>
        </nav>
        <div className="hd-end">
          <ThemeSwitch variant="icon" />
          <a href={links.findJobs} {...buttonProps('ghost', 'md', 'hd-cta-secondary')}>
            Find jobs
          </a>
          <a href={links.hrSignIn} {...buttonProps('primary', 'md', 'hd-cta')}>
            <span className="ui-btn-label">HR portal</span>
            <Icon icon={ArrowRight} size={16} className="ui-btn-trailing" />
          </a>
          <IconButton
            ref={menuButton}
            className="hd-menu-btn"
            label={menuOpen ? 'Close menu' : 'Open menu'}
            icon={menuOpen ? X : Menu}
            aria-expanded={menuOpen}
            aria-controls="site-menu"
            noTooltip
            onClick={() => setMenuOpen(o => !o)}
          />
        </div>
      </div>
      <div id="site-menu" className="hd-panel" data-open={menuOpen || undefined} hidden={!menuOpen}>
        <nav aria-label="Site menu" className="wrap">
          <ul className="hd-panel-links">{navLinks(() => setMenuOpen(false))}</ul>
          <div className="hd-panel-ctas">
            <a href={links.hrSignIn} {...buttonProps('primary', 'lg')} data-block="">
              <span className="ui-btn-label">For companies: HR portal</span>
            </a>
            <a href={links.findJobs} {...buttonProps('secondary', 'lg')} data-block="">
              <span className="ui-btn-label">For candidates: find jobs</span>
            </a>
          </div>
        </nav>
      </div>
    </header>
  )
}
