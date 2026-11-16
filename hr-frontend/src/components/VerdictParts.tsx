import type { ReactNode } from 'react'
import type { DomainTrust, IntegrityNote, VerdictReason, VerificationEvidence } from '../api/types'
import { CodeField, Quote } from '../design/components'
import {
  ArrowUpRight, Award, Building2, CircleMinus, EyeOff, FileText, Github, Gitlab, Globe, Icon, KeyRound, KeySquare, ShieldCheck, UserRoundCheck,
  type IconComponent,
} from '../design/icons'
import { plural, relativeTime } from '../design/lib/format'
import { safeHttpUrl } from '../lib/format'
import './verdict-parts.css'

/*
 * Staff verification building blocks (Part B), shared by the applicant Verification tab and the admin shadow queue.
 * Blind-first: `evidence === null` means the server withheld it; only the count is shown. Wording stays neutral:
 * integrity notes are "observations for a conversation, not accusations"; Not found is never negative.
 */

export const SOURCE_ICON: Record<string, IconComponent> = {
  GITHUB: Github, GITLAB: Gitlab, WEBSITE: Globe, DOCUMENT: FileText, OPEN_BADGE: Award, REFERENCE: UserRoundCheck,
}

export function OwnershipLine({ proven }: { proven: boolean }) {
  return proven ? (
    <span className="vp-own" data-proven="">
      <Icon icon={KeyRound} size={14} />Ownership proven
    </span>
  ) : (
    <span className="vp-own" title="The candidate has not proven they own this source, so the result is capped at Partly verified.">
      <Icon icon={KeySquare} size={14} />Ownership not proven<span className="vp-own-sub"> · capped at Partly verified</span>
    </span>
  )
}

/** Short words for the domain-trust check (WP-AG-NOTES "For the redesign agents"). */
export function domainTrustWords(t: DomainTrust): { label: string; tone: 'trusted' | 'org' | 'weak' } {
  const subject = t.check === 'BADGE_ISSUER' ? 'Issuer' : 'Employer domain'
  if (t.status === 'TRUSTED') {
    return t.trustedBy === 'ORGANIZATION'
      ? { label: `${subject} trusted by your organisation`, tone: 'org' }
      : { label: `${subject} trusted by automated checks`, tone: 'trusted' }
  }
  return { label: `${subject} not established`, tone: 'weak' }
}

export function DomainTrustChip({ trust }: { trust: DomainTrust }) {
  const words = domainTrustWords(trust)
  return (
    <span className="vp-trust" data-tone={words.tone}>
      <Icon icon={words.tone === 'weak' ? CircleMinus : words.tone === 'org' ? Building2 : ShieldCheck} size={14} />
      {words.label}
    </span>
  )
}

const NOTE_LABEL: Record<string, string> = {
  EMPLOYER_DOMAIN_UNPROVEN: 'Employer domain not shown to belong to the employer',
  EMPLOYER_DOMAIN_NEW: 'Employer domain registered recently',
  ISSUER_UNTRUSTED: 'Badge issuer is not an established issuer',
  ISSUER_NOT_DOMAIN_BOUND: 'Badge issuer is not tied to a domain',
  DOMAIN_MATCH: 'Referee’s email domain matches the employer',
}

export function noteLabel(code: string): string | null {
  return NOTE_LABEL[code] ?? null
}

type VerdictLike = {
  reasons: VerdictReason[]
  integrityNotes: IntegrityNote[]
  probeQuestion: string | null
  evidence: VerificationEvidence[] | null
  evidenceCount: number
  candidateNote?: string | null
  domainTrust?: DomainTrust | null
}

/** Why · Worth asking about · Suggested question · Evidence · Domain check · Candidate's note, as one definition list. */
export function VerdictDetails({ verdict, revealed, blindText }: { verdict: VerdictLike; revealed: boolean; blindText?: string }) {
  const rows: { term: string; body: ReactNode }[] = []
  if (verdict.reasons.length) {
    rows.push({
      term: 'Why',
      body: (
        <ul className="vp-reasons">
          {verdict.reasons.map((r, i) => (
            <li key={i}>
              <p>{r.text}{r.evidenceKeys.length > 0 && <span className="vp-keys">{r.evidenceKeys.map(k => <span key={k} className="mono">{k}</span>)}</span>}</p>
              {r.quote && <Quote className="vp-quote">{r.quote}</Quote>}
            </li>
          ))}
        </ul>
      ),
    })
  }
  if (verdict.integrityNotes.length) {
    rows.push({
      term: 'Worth asking about',
      body: (
        <>
          <p className="vp-muted">Observations for a conversation, not accusations. There are often ordinary explanations.</p>
          <ul className="vp-notes">
            {verdict.integrityNotes.map((n, i) => (
              <li key={`${n.code}-${i}`}>{noteLabel(n.code) && <strong>{noteLabel(n.code)}. </strong>}{n.text}</li>
            ))}
          </ul>
        </>
      ),
    })
  }
  if (verdict.probeQuestion) rows.push({ term: 'Suggested question', body: <p className="vp-probe">{verdict.probeQuestion}</p> })
  rows.push({
    term: 'Evidence',
    // Blind-first, defended twice: the server withholds evidence before reveal; if it ever sent some, it is not shown.
    body: verdict.evidence == null || !revealed ? (
      <p className="vp-muted vp-blind"><Icon icon={EyeOff} size={14} />{plural(verdict.evidenceCount, 'evidence item')} · {blindText ?? 'sources appear after identity is revealed'}</p>
    ) : verdict.evidence.length === 0 ? (
      <p className="vp-muted">No evidence items are linked to this result.</p>
    ) : (
      <EvidenceItems items={verdict.evidence} />
    ),
  })
  if (verdict.domainTrust) {
    const t = verdict.domainTrust
    rows.push({
      term: t.check === 'BADGE_ISSUER' ? 'Issuer check' : 'Employer domain',
      body: (
        <>
          <p className="vp-trust-line">
            <DomainTrustChip trust={t} />
            {t.domain && revealed ? <span className="mono vp-domain">{t.domain}</span> : !revealed ? <span className="vp-muted">domain shown after reveal</span> : null}
          </p>
          {t.liftedByOrganization && <p className="vp-muted">Shown as Verified because your organisation trusts this domain. Other organisations see Partly verified.</p>}
          {t.candidateControlled && <p className="vp-muted">The domain belongs to a site the candidate proved they own, so it cannot vouch for an employer.</p>}
          {t.reasons.length > 0 && <ul className="vp-notes">{t.reasons.map((r, i) => <li key={i}>{r}</li>)}</ul>}
        </>
      ),
    })
  }
  if (verdict.candidateNote && revealed) rows.push({ term: 'Candidate’s note', body: <p className="vp-cnote">“{verdict.candidateNote}”</p> })

  return (
    <dl className="vp-details">
      {rows.map(r => (
        <div key={r.term} className="vp-row">
          <dt>{r.term}</dt>
          <dd>{r.body}</dd>
        </div>
      ))}
    </dl>
  )
}

export function EvidenceItems({ items }: { items: VerificationEvidence[] }) {
  return (
    <ul className="vp-evidence">
      {items.map(item => {
        const url = safeHttpUrl(item.url)
        const title = item.title || item.url || item.kind
        return (
          <li key={item.id}>
            <span className="vp-ev-key mono" aria-label={`Evidence ${item.key}`}>{item.key}</span>
            <div className="vp-ev-main">
              {url ? (
                <a href={url} target="_blank" rel="noopener noreferrer nofollow">{title} <Icon icon={ArrowUpRight} size={12} /><span className="sr-only"> (opens in a new tab)</span></a>
              ) : <strong>{title}</strong>}
              <span className="vp-muted">{item.kind.replace(/_/g, ' ').toLowerCase()} · retrieved {relativeTime(item.retrievedAt)}</span>
              {item.excerpt && <span className="vp-excerpt">{item.excerpt}</span>}
              <CodeField value={item.contentHash} truncate="middle" className="vp-hash" />
            </div>
          </li>
        )
      })}
    </ul>
  )
}
