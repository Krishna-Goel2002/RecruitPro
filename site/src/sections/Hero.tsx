import brand from '../design/brand/active'
import { ArrowRight, Building2, Check, Icon, UserRoundCheck } from '../design/icons'
import type { PortalLinks } from '../site/config'
import { HeroPreview } from './HeroPreview'

/** Hero: one sentence on what it is, the two doors (companies / candidates) and the product preview. */
export function Hero({ links }: { links: PortalLinks }) {
  return (
    <section className="hero" aria-labelledby="hero-title">
      <div className="wrap hero-grid">
        <div className="hero-copy">
          <p className="overline hero-in" style={{ ['--h' as string]: 0 }}>
            {brand.tagline}
          </p>
          <h1 id="hero-title" className="hero-title hero-in" style={{ ['--h' as string]: 1 }}>
            See the evidence behind every application.
          </h1>
          <p className="hero-lead hero-in" style={{ ['--h' as string]: 2 }}>
            {brand.name} ranks applicants by how much of each requirement is backed by evidence, from the resume to
            verified sources, work samples and structured interviews, and shows where every piece came from. Names stay
            hidden until you shortlist.
          </p>
          <div className="hero-doors hero-in" style={{ ['--h' as string]: 3 }}>
            <a className="door ui-lift" data-primary="" href={links.hrSignIn}>
              <Icon icon={Building2} size={20} className="door-icon" />
              <span className="door-text">
                <span className="door-kicker">For companies</span>
                <span className="door-label">Open the HR portal</span>
              </span>
              <Icon icon={ArrowRight} size={20} className="door-arrow" />
            </a>
            <a className="door ui-lift" href={links.findJobs}>
              <Icon icon={UserRoundCheck} size={20} className="door-icon" />
              <span className="door-text">
                <span className="door-kicker">For candidates</span>
                <span className="door-label">Find jobs</span>
              </span>
              <Icon icon={ArrowRight} size={20} className="door-arrow" />
            </a>
          </div>
          <ul className="hero-points hero-in" style={{ ['--h' as string]: 4 }}>
            {['Blind review until shortlist', 'Candidates choose what to share', 'A person makes every decision'].map(p => (
              <li key={p}>
                <Icon icon={Check} size={16} />
                {p}
              </li>
            ))}
          </ul>
        </div>
        <div className="hero-visual hero-in" style={{ ['--h' as string]: 2 }}>
          <HeroPreview />
        </div>
      </div>
    </section>
  )
}
