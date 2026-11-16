import { buttonProps, Badge, EvidenceLadder, VerdictChip } from '../design/components'
import { ArrowRight, Eye, Icon, KeyRound, ShieldCheck, Upload, type IconComponent } from '../design/icons'
import type { PortalLinks } from '../site/config'
import { Reveal } from '../site/reveal'

const POINTS: { icon: IconComponent; title: string; body: string }[] = [
  {
    icon: Upload,
    title: 'Strengthen your application',
    body: 'Add GitHub or GitLab, a portfolio site, a certificate, an Open Badge or a referee. Before you apply, see which requirements of a role your evidence already covers.',
  },
  {
    icon: KeyRound,
    title: 'Nothing without your consent',
    body: 'Every source is connected with your explicit consent and only its public data is read. Revoke a source at any time and its stored evidence is deleted.',
  },
  {
    icon: Eye,
    title: 'Preview before you share',
    body: 'Every result waits for your review. You see exactly what an employer would see, then share it or keep it hidden.',
  },
  {
    icon: ShieldCheck,
    title: 'Judged on the work',
    body: 'Reviewers see your evidence before your name. A person reviews every application, and a source that does not mention a skill never counts against you.',
  },
]

/** A candidate's review of one verification result (example). Static: the buttons are drawings, not controls. */
function ReviewCard() {
  return (
    <figure className="rv">
      <figcaption className="sr-only">
        Example: a verified GitHub result for the claim Java 17, waiting for the candidate to share it or hide it.
      </figcaption>
      <div className="rv-card" aria-hidden="true">
        <div className="rv-head">
          <span className="rv-claim">Java 17</span>
          <Badge tone="info">Waiting for your review</Badge>
          <Badge tone="neutral" className="rv-example">Example</Badge>
        </div>
        <div className="rv-line">
          <VerdictChip verdict="VERIFIED" size="sm" />
          <span className="rv-src">github.com · ownership proven</span>
        </div>
        <div className="rv-why">
          <p className="rv-label">Why</p>
          <p className="rv-quote">Java appears in public repositories on this account, and the account is proven to be yours.</p>
        </div>
        <div className="rv-employer">
          <p className="rv-label">What employers see if you share</p>
          <ul>
            <li>The verdict and the reasons above, without your name or links</li>
            <li>Links and excerpts only once your identity is revealed, at shortlist</li>
          </ul>
        </div>
        <div className="rv-ladder">
          <span>If a recruiter confirms it</span>
          <EvidenceLadder tier="CORROBORATED" audience="candidate" animate={false} />
        </div>
        <div className="rv-actions">
          <span {...buttonProps('ghost', 'md')}>Hide</span>
          <span {...buttonProps('primary', 'md')}>Share with employers</span>
        </div>
      </div>
    </figure>
  )
}

export function ForCandidates({ links }: { links: PortalLinks }) {
  return (
    <section id="candidates" className="band" data-tone="sunken" aria-labelledby="candidates-title">
      <div className="wrap split">
        <div className="split-text">
          <Reveal className="sec-head" data-tight="">
            <p className="overline">For candidates</p>
            <h2 id="candidates-title" className="sec-title">
              Show what you can do, on your terms.
            </h2>
          </Reveal>
          <ul className="cand-points">
            {POINTS.map((p, i) => (
              <Reveal as="li" key={p.title} index={i} className="cand-point">
                <span className="point-icon">
                  <Icon icon={p.icon} size={20} />
                </span>
                <div>
                  <h3 className="point-title">{p.title}</h3>
                  <p className="point-body">{p.body}</p>
                </div>
              </Reveal>
            ))}
          </ul>
          <Reveal className="cand-ctas">
            <a href={links.findJobs} {...buttonProps('primary', 'lg')}>
              <span className="ui-btn-label">Find jobs</span>
              <Icon icon={ArrowRight} size={16} className="ui-btn-trailing" />
            </a>
            <a href={links.register} {...buttonProps('secondary', 'lg')}>
              <span className="ui-btn-label">Create an account</span>
            </a>
          </Reveal>
        </div>
        <Reveal className="split-visual" index={1}>
          <ReviewCard />
        </Reveal>
      </div>
    </section>
  )
}
