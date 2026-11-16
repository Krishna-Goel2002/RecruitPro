import brand from '../design/brand/active'
import { ThemeSwitch } from '../design/components'
import type { PortalLinks } from '../site/config'
import { NAV } from './Header'

export function Footer({ links }: { links: PortalLinks }) {
  return (
    <footer className="ft">
      <div className="wrap">
        <div className="ft-cta">
          <h2 className="ft-cta-title">Hire on evidence you can trace.</h2>
          <div className="ft-cta-actions">
            <a href={links.hrSignIn} className="ui-btn" data-variant="primary" data-size="lg">
              <span className="ui-btn-label">For companies: HR portal</span>
            </a>
            <a href={links.findJobs} className="ui-btn" data-variant="secondary" data-size="lg">
              <span className="ui-btn-label">For candidates: find jobs</span>
            </a>
          </div>
        </div>
        <div className="ft-grid">
          <div className="ft-brand">
            <span className="ft-wordmark">
              <brand.Mark size={22} />
              <span className="p-wordmark">{brand.name}</span>
            </span>
            <p className="ft-tagline">{brand.tagline}.</p>
          </div>
          <nav aria-label="Footer" className="ft-cols">
            <div>
              <h2 className="ft-h">On this page</h2>
              <ul>
                {NAV.map(n => (
                  <li key={n.id}>
                    <a href={`#${n.id}`}>{n.label}</a>
                  </li>
                ))}
              </ul>
            </div>
            <div>
              <h2 className="ft-h">Companies</h2>
              <ul>
                <li>
                  <a href={links.hrSignIn}>Sign in to the HR portal</a>
                </li>
              </ul>
              <h2 className="ft-h">Candidates</h2>
              <ul>
                <li>
                  <a href={links.findJobs}>Find jobs</a>
                </li>
                <li>
                  <a href={links.register}>Create an account</a>
                </li>
                <li>
                  <a href={links.candidateSignIn}>Sign in</a>
                </li>
              </ul>
            </div>
          </nav>
          <div className="ft-theme">
            <ThemeSwitch />
          </div>
        </div>
        <p className="ft-fine">
          Examples on this page use fictional data. Coverage is the share of requirements with evidence, not a probability
          of success.
        </p>
      </div>
    </footer>
  )
}
