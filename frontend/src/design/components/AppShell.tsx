import { useEffect, useRef, useState, type ReactNode } from 'react'
import { Link, NavLink, useLocation } from 'react-router-dom'
import brand from '../brand/active'
import { Icon, Menu as MenuIcon, PanelLeftClose, PanelLeftOpen, X, type IconComponent } from '../icons'
import { formatNumber } from '../lib/format'
import { cx } from './cx'
import { useMediaQuery, useStoredState } from './hooks'
import { IconButton } from './IconButton'
import { Tooltip } from './Tooltip'

export interface NavItem {
  /** Router path. */
  to: string
  /** Plain in-page link instead of a router NavLink (e.g. "#buttons"); pair with `current`. */
  href?: string
  /** Force the current state (only for `href` items; router items derive it from the URL). */
  current?: boolean
  label: string
  icon: IconComponent
  /** Count shown at the end of the item (unread, waiting). 0 hides it. */
  count?: number
  /** Accessible suffix for the count, e.g. "unread". */
  countLabel?: string
  /** Match the path exactly (NavLink `end`). */
  end?: boolean
}

export interface NavGroup {
  /** Group label (caption, subtle, sentence case). */
  label?: string
  items: NavItem[]
}

export interface AppShellProps {
  portal: 'candidate' | 'staff'
  nav: NavGroup[]
  /** Where the brand links to. */
  homeHref?: string
  /** Top bar, left on ≥ 900 px: breadcrumb or context. */
  topbarStart?: ReactNode
  /** Top bar, right: bell, account menu. */
  topbarEnd?: ReactNode
  /** Sidebar foot: organisation name (HR) or nothing. */
  sidebarFooter?: ReactNode
  /** Page-level banner above the content (verify email, shadow mode). */
  banner?: ReactNode
  /** 900–1199 px: 'rail' (64 px icon rail with tooltips, HR) or 'drawer' (menu button, candidate). */
  midMode?: 'rail' | 'drawer'
  children: ReactNode
}

function Brand({ portal, compact }: { portal: AppShellProps['portal']; compact?: boolean }) {
  const label = brand.portalLabel[portal]
  return (
    <span className="p-brand" data-compact={compact || undefined}>
      <brand.Mark size={20} />
      <span className="p-wordmark">{brand.name}</span>
      {label && <span className="p-portal-label">{label}</span>}
    </span>
  )
}

function NavList({ nav, rail, onNavigate }: { nav: NavGroup[]; rail: boolean; onNavigate?: () => void }) {
  return (
    <>
      {nav.map((group, gi) => (
        <div className="p-nav-group" key={group.label ?? gi}>
          {group.label && <p className="p-nav-group-label">{group.label}</p>}
          <ul role="list">
            {group.items.map(item => {
              const count = item.count && item.count > 0 ? item.count : 0
              const inner = (
                <>
                  <Icon icon={item.icon} size={16} className="p-nav-icon" />
                  <span className="p-nav-label">{item.label}</span>
                  {count > 0 && (
                    <span className="p-nav-count numeric">
                      <span aria-hidden="true">{count > 99 ? '99+' : formatNumber(count)}</span>
                      <span className="sr-only">
                        , {count} {item.countLabel ?? ''}
                      </span>
                    </span>
                  )}
                </>
              )
              const link = item.href ? (
                <a href={item.href} className="p-nav-item" aria-current={item.current ? 'page' : undefined} onClick={onNavigate}>
                  {inner}
                </a>
              ) : (
                <NavLink to={item.to} end={item.end} className="p-nav-item" onClick={onNavigate}>
                  {inner}
                </NavLink>
              )
              return (
                <li key={item.href ?? item.to}>
                  {rail ? (
                    <Tooltip content={item.label} side="top" align="start" labelOnly>
                      {link}
                    </Tooltip>
                  ) : (
                    link
                  )}
                </li>
              )
            })}
          </ul>
        </div>
      ))}
    </>
  )
}

/**
 * App shell (§6) shared by both portals: sidebar (240 px, collapsible to a 64 px rail on ≥ 1200 px, persisted per
 * viewer), a 64 px rail or the drawer at 900–1199 px, and below 900 px a 52 px top bar whose Menu button opens a
 * left drawer (native modal <dialog>: inert page, Escape, focus return). The content is keyed by pathname for the
 * route entrance (M1), and after navigation focus moves to the page H1.
 */
export function AppShell({ portal, nav, homeHref = '/', topbarStart, topbarEnd, sidebarFooter, banner, midMode = 'rail', children }: AppShellProps) {
  const location = useLocation()
  const wide = useMediaQuery('(min-width: 1200px)')
  const mid = useMediaQuery('(min-width: 900px)')
  const [collapsedPref, setCollapsedPref] = useStoredState<'true' | 'false'>('sidebar-collapsed', 'false', ['true', 'false'])
  const collapsed = collapsedPref === 'true'
  const rail = wide ? collapsed : mid && midMode === 'rail'
  const drawerMode = !mid || (!wide && midMode === 'drawer')

  const drawerRef = useRef<HTMLDialogElement>(null)
  const menuBtn = useRef<HTMLButtonElement>(null)
  const [drawerOpen, setDrawerOpen] = useState(false)
  const firstRender = useRef(true)
  const mainRef = useRef<HTMLElement>(null)

  // Drawer open/close through the native modal dialog.
  useEffect(() => {
    const d = drawerRef.current
    if (!d) return
    if (drawerOpen && !d.open) d.showModal()
    else if (!drawerOpen && d.open) {
      d.close()
      menuBtn.current?.focus()
    }
  }, [drawerOpen])
  useEffect(() => {
    if (!drawerMode) setDrawerOpen(false)
  }, [drawerMode])

  // Route change: close the drawer, move focus to the new page's H1 (or main), scroll to top.
  useEffect(() => {
    setDrawerOpen(false)
    if (firstRender.current) {
      firstRender.current = false
      return
    }
    const t = window.setTimeout(() => {
      const main = mainRef.current
      const h1 = main?.querySelector<HTMLElement>('[data-page-heading], h1')
      if (h1) {
        if (!h1.hasAttribute('tabindex')) h1.setAttribute('tabindex', '-1')
        h1.focus({ preventScroll: true })
      } else main?.focus({ preventScroll: true })
      window.scrollTo({ top: 0 })
    }, 0)
    return () => window.clearTimeout(t)
  }, [location.pathname])

  return (
    <div className="p-shell" data-rail={rail || undefined} data-mid={midMode}>
      <a className="p-skip" href="#main">
        Skip to main content
      </a>

      <aside className="p-sidebar" aria-label="Sidebar">
        <div className="p-sidebar-inner">
          <div className="p-sidebar-brand">
            <Link to={homeHref} className="p-brand-link">
              <Brand portal={portal} compact={rail} />
            </Link>
          </div>
          <nav className="p-nav" aria-label="Primary">
            <NavList nav={nav} rail={rail} />
          </nav>
          <div className="p-sidebar-footer">
            {!rail && sidebarFooter && <div className="p-sidebar-footer-content">{sidebarFooter}</div>}
            {wide && (
              <IconButton
                className="p-collapse-btn"
                label={collapsed ? 'Expand sidebar' : 'Collapse sidebar'}
                icon={collapsed ? PanelLeftOpen : PanelLeftClose}
                size="sm"
                aria-expanded={!collapsed}
                onClick={() => setCollapsedPref(collapsed ? 'false' : 'true')}
              />
            )}
          </div>
        </div>
      </aside>

      <div className="p-main-col">
        <header className="p-topbar">
          {drawerMode && (
            <IconButton
              ref={menuBtn}
              className="p-menu-btn"
              label="Menu"
              icon={MenuIcon}
              aria-expanded={drawerOpen}
              aria-controls="p-nav-drawer"
              onClick={() => setDrawerOpen(true)}
              noTooltip
            />
          )}
          {drawerMode && (
            <Link to={homeHref} className="p-topbar-brand">
              <Brand portal={portal} />
            </Link>
          )}
          <div className="p-topbar-start">{topbarStart}</div>
          <div className="p-topbar-end">{topbarEnd}</div>
        </header>
        {banner && <div className="p-banner">{banner}</div>}
        <main ref={mainRef} id="main" className="p-main" tabIndex={-1}>
          <div className="p-content" key={location.pathname}>
            {children}
          </div>
        </main>
      </div>

      <dialog
        ref={drawerRef}
        id="p-nav-drawer"
        className="p-nav-drawer"
        aria-label="Navigation"
        onCancel={e => {
          e.preventDefault()
          setDrawerOpen(false)
        }}
        onClick={e => e.target === e.currentTarget && setDrawerOpen(false)}
      >
        <div className="p-nav-drawer-panel">
          <div className="p-sidebar-brand">
            <Brand portal={portal} />
            <IconButton label="Close menu" icon={X} size="sm" onClick={() => setDrawerOpen(false)} noTooltip />
          </div>
          <nav className="p-nav" aria-label="Primary">
            <NavList nav={nav} rail={false} onNavigate={() => setDrawerOpen(false)} />
          </nav>
          {sidebarFooter && <div className={cx('p-sidebar-footer')}><div className="p-sidebar-footer-content">{sidebarFooter}</div></div>}
        </div>
      </dialog>
    </div>
  )
}
