import { useEffect } from 'react'
import brand from '../design/brand/active'
import { buttonProps, ThemeSwitch } from '../design/components'
import { ArrowLeft, Icon } from '../design/icons'
import type { PortalLinks } from '../site/config'

/**
 * Paths the landing page itself answers (its base, "/" behind the gateway and on its own port); nginx serves
 * index.html for every other path too.
 */
export function isLandingPath(pathname: string, base: string = import.meta.env.BASE_URL): boolean {
  const root = base.endsWith('/') ? base : `${base}/`
  return pathname === root || pathname === root.slice(0, -1) || pathname === `${root}index.html`
}

/**
 * Unknown address (nginx falls back to index.html for every path). Before, /pricing or a mistyped link showed the
 * whole landing page under the wrong address, and its #section links pointed at /pricing#how, which went nowhere.
 * This says so plainly and offers the three places people usually want.
 */
export function NotFound({ links }: { links: PortalLinks }) {
  useEffect(() => {
    document.title = `Page not found · ${brand.name}`
  }, [])
  return (
    <div className="site" id="top">
      <header className="hd">
        <div className="hd-bar wrap">
          <a href={links.home} className="hd-brand" aria-label={`${brand.name}, home`}>
            <brand.Mark size={22} />
            <span className="p-wordmark">{brand.name}</span>
          </a>
          <span className="grow" />
          <ThemeSwitch variant="icon" />
        </div>
      </header>
      <main id="main" tabIndex={-1} className="nf">
        <div className="wrap">
          <p className="nf-code numeric">404</p>
          <h1 className="sec-title">There is nothing at this address</h1>
          <p className="sec-lead">The link may be old or mistyped. These are the places people usually look for.</p>
          <div className="ft-cta-actions nf-actions">
            <a href={links.home} {...buttonProps('primary', 'lg')}>
              <Icon icon={ArrowLeft} size={16} />
              <span className="ui-btn-label">Back to the home page</span>
            </a>
            <a href={links.findJobs} {...buttonProps('secondary', 'lg')}>
              <span className="ui-btn-label">For candidates: find jobs</span>
            </a>
            <a href={links.hrSignIn} {...buttonProps('secondary', 'lg')}>
              <span className="ui-btn-label">For companies: HR sign in</span>
            </a>
          </div>
        </div>
      </main>
    </div>
  )
}
