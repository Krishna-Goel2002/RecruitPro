import { useEffect, useState } from 'react'
import { buttonProps, CountUp, EvidenceLadder } from '../design/components'
import { Check, Icon, ListChecks, Rows3, Scale, Search, Users, type IconComponent } from '../design/icons'
import { reduced } from '../design/motion'
import type { PortalLinks } from '../site/config'
import { Reveal, useSeen } from '../site/reveal'

/** Ranking groups, as the HR portal names them (example counts). Bars grow and numbers count up on first view. */
function RankingDemo() {
  const { ref, seen } = useSeen<HTMLDivElement>({ threshold: 0.4 })
  const groups = [
    { label: 'Ranked', n: 18 },
    { label: 'Must-haves not yet verified', n: 7 },
    { label: 'Unscored', n: 3 },
  ]
  const max = 18
  return (
    <div ref={ref} className="rk" data-state={seen ? 'shown' : 'pending'}>
      <p className="demo-cap">Example posting · 28 applicants</p>
      <ul>
        {groups.map((g, i) => (
          <li key={g.label} className="rk-row" style={{ ['--i' as string]: i }}>
            <span className="rk-label">{g.label}</span>
            <span className="rk-track" aria-hidden="true">
              <span className="rk-fill" style={{ ['--v' as string]: g.n / max }} />
            </span>
            <CountUp value={g.n} className="rk-n numeric" />
          </li>
        ))}
      </ul>
    </div>
  )
}

/** The assistant's confirm-before-change contract, as a static example exchange. */
function AssistantDemo() {
  const { ref, seen } = useSeen<HTMLDivElement>({ threshold: 0.5 })
  const [step, setStep] = useState(() => (reduced() ? 2 : 0))
  useEffect(() => {
    if (!seen || step >= 2) return
    const t = window.setTimeout(() => setStep(s => s + 1), step === 0 ? 350 : 650)
    return () => window.clearTimeout(t)
  }, [seen, step])
  return (
    <div ref={ref} className="as" aria-hidden="true">
      <p className="as-msg" data-from="user" data-in={step >= 1 || undefined}>
        Shortlist A-3014 for Backend Engineer
      </p>
      <div className="as-card" data-in={step >= 2 || undefined}>
        <p className="as-card-title">Proposed action</p>
        <p className="as-card-body">Move A-3014 from Applied to Shortlisted. Identity will be revealed and the change audited.</p>
        <div className="as-card-actions">
          <span {...buttonProps('ghost', 'sm')}>Cancel</span>
          <span {...buttonProps('primary', 'sm')}>
            <Icon icon={Check} size={14} />
            <span className="ui-btn-label">Confirm</span>
          </span>
        </div>
      </div>
    </div>
  )
}

function RubricDemo() {
  const anchors = ['Does not apply it', 'Applies it with help', 'Applies it correctly', 'Applies it skilfully, explains trade-offs']
  return (
    <ol className="rb" aria-label="Example rubric anchors for one requirement">
      {anchors.map((a, i) => (
        <li key={a} className="rb-row" data-counts={i >= 2 || undefined} style={{ ['--i' as string]: i }}>
          <span className="rb-n numeric" aria-hidden="true">
            {i + 1}
          </span>
          <span className="rb-text">
            <span className="sr-only">Level {i + 1}: </span>
            {a}
          </span>
        </li>
      ))}
    </ol>
  )
}

function FairnessDemo() {
  const { ref, seen } = useSeen<HTMLDivElement>({ threshold: 0.5 })
  return (
    <div ref={ref} className="fr" data-state={seen ? 'shown' : 'pending'} aria-hidden="true">
      <p className="demo-cap">Example · adverse-impact ratio by stage</p>
      {[
        { stage: 'Shortlisted', r: 0.92 },
        { stage: 'Interviewing', r: 0.85 },
        { stage: 'Selected', r: 0.74 },
      ].map((s, i) => (
        <div key={s.stage} className="fr-row" data-flag={s.r < 0.8 || undefined} style={{ ['--i' as string]: i }}>
          <span className="fr-stage">{s.stage}</span>
          <span className="fr-track">
            <span className="fr-fill" style={{ ['--v' as string]: s.r }} />
            <span className="fr-line" />
          </span>
          <span className="fr-r numeric">{s.r.toFixed(2)}</span>
        </div>
      ))}
      <p className="fr-legend">
        <span className="fr-line-key" /> 0.80 line (four-fifths rule): a reason to look closer, not proof of bias
      </p>
    </div>
  )
}

const FEATURES: { id: string; icon: IconComponent; title: string; body: string; demo: JSX.Element; wide?: boolean }[] = [
  {
    id: 'ranking',
    icon: Rows3,
    title: 'A ranking you can explain',
    body: 'Applicants are ordered by verified coverage. Anyone whose gate requirements are not yet verified waits in a group of their own instead of being rejected. Every number opens to the requirements and sources behind it.',
    demo: <RankingDemo />,
    wide: true,
  },
  {
    id: 'assistant',
    icon: Search,
    title: 'An assistant that asks first',
    body: 'Ask how a posting is doing, what needs attention today, or about one applicant. It works as you, with your permissions, refers to candidates by application code, and shows a preview before any change. Nothing happens until you press Confirm.',
    demo: <AssistantDemo />,
    wide: true,
  },
  {
    id: 'work-samples',
    icon: ListChecks,
    title: 'Work samples',
    body: 'A short, timed task generated from the job’s must-haves, with four anchored levels per requirement. A score of 3 or 4 marks that requirement as demonstrated.',
    demo: <RubricDemo />,
  },
  {
    id: 'interviews',
    icon: Users,
    title: 'Structured interviews',
    body: 'One kit per job: behavioural questions with anchored ratings from 1 to 4. Interviewers see the requirements without evidence first. Ratings tied to a requirement, not overall impressions, count as interview-verified.',
    demo: (
      <div className="iv" aria-hidden="true">
        <EvidenceLadder tier="INTERVIEW_VERIFIED" />
        <span className="iv-q">“Tell me about a recent piece of work where you used PostgreSQL. What did you do yourself?”</span>
      </div>
    ),
  },
  {
    id: 'fairness',
    icon: Scale,
    title: 'Fairness reports',
    body: 'Selection rates by stage from a voluntary survey that is stored apart from applications and never used in ranking. Groups with fewer than five people are suppressed.',
    demo: <FairnessDemo />,
  },
]

export function ForTeams({ links }: { links: PortalLinks }) {
  return (
    <section id="teams" className="band" aria-labelledby="teams-title">
      <div className="wrap">
        <Reveal className="sec-head" data-split="">
          <div>
            <p className="overline">For HR teams</p>
            <h2 id="teams-title" className="sec-title">
              From a pile of resumes to a shortlist you can defend.
            </h2>
          </div>
          <a href={links.hrSignIn} {...buttonProps('secondary', 'lg', 'sec-cta')}>
            <span className="ui-btn-label">Open the HR portal</span>
          </a>
        </Reveal>
        <ul className="feat-grid">
          {FEATURES.map((f, i) => (
            <Reveal as="li" key={f.id} index={i % 3} className="feat" data-wide={f.wide || undefined}>
              <div className="feat-text">
                <span className="feat-icon">
                  <Icon icon={f.icon} size={20} />
                </span>
                <h3 className="feat-title">{f.title}</h3>
                <p className="feat-body">{f.body}</p>
              </div>
              <div className="feat-demo">{f.demo}</div>
            </Reveal>
          ))}
        </ul>
      </div>
    </section>
  )
}
