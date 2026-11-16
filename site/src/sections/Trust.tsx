import { EyeOff, Icon, KeyRound, ScrollText, ShieldCheck, type IconComponent } from '../design/icons'
import { Reveal } from '../site/reveal'

const ITEMS: { icon: IconComponent; title: string; body: string }[] = [
  {
    icon: EyeOff,
    title: 'Blind-first review',
    body: 'Names, contact details, links and the resume file stay hidden from reviewers until an applicant is shortlisted. Until then, applicants are referred to by code, such as A-3014.',
  },
  {
    icon: KeyRound,
    title: 'Consent and control',
    body: 'Candidates connect sources with explicit consent, decide which results employers see, and can export or delete their data at any time.',
  },
  {
    icon: ShieldCheck,
    title: 'De-identified AI',
    body: 'Names, emails, phone numbers, links, ages and gendered pronouns are removed before text reaches any model. Matching is deterministic and needs no external model; external models are optional and off unless an organisation turns them on.',
  },
  {
    icon: ScrollText,
    title: 'Audit trail',
    body: 'Identity reveals, decisions, file downloads, verification reviews and setting changes are recorded with who did them and when.',
  },
]

export function Trust() {
  return (
    <section id="trust" className="band" aria-labelledby="trust-title">
      <div className="wrap">
        <Reveal className="sec-head">
          <p className="overline">Trust and privacy</p>
          <h2 id="trust-title" className="sec-title">
            Fair by default, not by setting.
          </h2>
        </Reveal>
        <ul className="cols-4">
          {ITEMS.map((t, i) => (
            <Reveal as="li" key={t.title} index={i} className="point">
              <span className="point-icon">
                <Icon icon={t.icon} size={20} />
              </span>
              <h3 className="point-title">{t.title}</h3>
              <p className="point-body">{t.body}</p>
            </Reveal>
          ))}
        </ul>
        <Reveal as="p" className="limits">
          <strong>What we do not claim.</strong> Coverage measures evidence, not ability or fit. Employer and institution
          names stay visible in evidence before shortlist. A fairness report is a reason to examine decisions, not proof
          of discrimination.
        </Reveal>
      </div>
    </section>
  )
}
