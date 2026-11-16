import { useEffect, useState } from 'react'
import { buttonProps, VerdictChip } from '../design/components'
import { Check, Icon } from '../design/icons'
import type { Verdict } from '../design/lib/labels'
import { reduced } from '../design/motion'
import { LadderSteps } from '../site/LadderSteps'
import { Reveal, useSeen } from '../site/reveal'

const CHECKS: { verdict: Verdict; claim: string; source: string }[] = [
  { verdict: 'VERIFIED', claim: 'Java 17', source: 'github.com · ownership proven' },
  { verdict: 'PARTLY_VERIFIED', claim: 'React', source: 'personal website · ownership not proven' },
  { verdict: 'NOT_FOUND', claim: 'Kubernetes', source: 'github.com · neutral, never a penalty' },
]

/** Verdict chips that resolve one after another when scrolled into view (M6). Reduced motion: resolved at once. */
function VerdictDemo() {
  const { ref, seen } = useSeen<HTMLUListElement>({ threshold: 0.4 })
  const [done, setDone] = useState(0)
  useEffect(() => {
    if (!seen || done >= CHECKS.length) return
    const t = window.setTimeout(() => setDone(d => d + 1), done === 0 ? 500 : 450)
    return () => window.clearTimeout(t)
  }, [seen, done])
  const settled = (i: number) => reduced() || (seen && done > i)
  return (
    <ul ref={ref} className="vd" aria-label="Example verification results">
      {CHECKS.map((c, i) => (
        <li key={c.claim} className="vd-row">
          <span className="vd-claim">{c.claim}</span>
          <VerdictChip verdict={c.verdict} state={settled(i) ? 'done' : 'checking'} size="sm" />
          <span className="vd-src">{c.source}</span>
        </li>
      ))}
    </ul>
  )
}

function DecisionDemo() {
  return (
    <div className="dd" aria-hidden="true">
      <div className="dd-row">
        <span className="dd-claim">Java 17</span>
        <VerdictChip verdict="VERIFIED" size="sm" />
        <span className="dd-src">shared by the candidate</span>
      </div>
      <LadderSteps tier="CORROBORATED" decorative className="dd-ladder" />
      <div className="dd-actions">
        <span className="dd-note">Confirming lifts the claim to Corroborated for your organisation only.</span>
        <span {...buttonProps('secondary', 'sm')}>Overrule</span>
        <span {...buttonProps('primary', 'sm', 'dd-confirm')}>
          <Icon icon={Check} size={14} />
          <span className="ui-btn-label">Confirm</span>
        </span>
      </div>
    </div>
  )
}

const STEPS = [
  {
    title: 'Every requirement gets an evidence level',
    body: 'Each requirement of the job is matched against the resume, the candidate’s own claims, verified sources, scored work samples and structured interviews. It lands on a five-step ladder, from a claim nobody has checked yet to something confirmed in an interview.',
    note: 'Coverage is the share of requirements with evidence, not a probability of success.',
  },
  {
    title: 'Candidates can strengthen it, with consent',
    body: 'A candidate can connect GitHub or GitLab, a website, a document, an Open Badge or a referee. Verification agents read public data only, cite every source with a stored, hashed snapshot and draft one verdict per claim. The candidate reviews each verdict and decides what employers see.',
    note: 'A source that does not mention a claim is neutral. It never lowers a ranking.',
  },
  {
    title: 'A recruiter makes the call',
    body: 'A shared verdict counts only after a recruiter confirms it, and only for that organisation. A gate requirement without evidence holds an applicant out of the ranked group; nobody is rejected automatically. Shortlisting, rejecting and selecting are always a person’s decision, and each one is recorded.',
    note: 'Identity is revealed on shortlist and the reveal is audited.',
  },
]

export function HowItWorks() {
  const visuals = [
    <LadderSteps key="l" tier="INTERVIEW_VERIFIED" describe className="how-ladder" />,
    <VerdictDemo key="v" />,
    <DecisionDemo key="d" />,
  ]
  return (
    <section id="how" className="band" data-tone="sunken" aria-labelledby="how-title">
      <div className="wrap">
        <Reveal className="sec-head">
          <p className="overline">How it works</p>
          <h2 id="how-title" className="sec-title">
            From a claim to a decision, in three visible steps.
          </h2>
          <p className="sec-lead">Each step is visible to the people it affects: the candidate sees their evidence, the recruiter sees its source.</p>
        </Reveal>
        <ol className="steps">
          {STEPS.map((s, i) => (
            <Reveal as="li" key={s.title} className="step" index={0}>
              <div className="step-text">
                <span className="step-num" aria-hidden="true">
                  {i + 1}
                </span>
                <h3 className="step-title">
                  <span className="sr-only">Step {i + 1}: </span>
                  {s.title}
                </h3>
                <p className="step-body">{s.body}</p>
                <p className="step-note">{s.note}</p>
              </div>
              <div className="step-visual">{visuals[i]}</div>
            </Reveal>
          ))}
        </ol>
      </div>
    </section>
  )
}
