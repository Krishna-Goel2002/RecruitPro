import type { ApplicantDetail, DecisionAction } from '../../api/types'
import { Button, EvidenceLadder, IconButton, Menu } from '../../design/components'
import { CalendarClock, CircleCheck, Ellipsis, GateOpen, Icon, Info, ShieldCheck } from '../../design/icons'
import { plural } from '../../design/lib/format'
import { ACTIONS, isGateOpen, orderRequirements, primaryFor } from './model'

const ORDER: DecisionAction[] = ['SHORTLIST', 'MOVE_TO_INTERVIEW', 'SELECT', 'UNDO_SHORTLIST', 'REOPEN', 'REJECT']

/**
 * The decision bar (FLOWS-HR-B §1 step 6): sticky at the bottom of the main column, so it is always reachable but
 * never sits above the evidence. One primary action for the status, secondary actions in "⋯", Reject as a quiet
 * danger button after a divider. Recruiters see the summary only; final states show their outcome.
 */
export function DecisionBar({ detail, canDecide, canSchedule, onAction, onSchedule, onHelp }: {
  detail: ApplicantDetail
  canDecide: boolean
  canSchedule: boolean
  onAction: (action: DecisionAction) => void
  onSchedule: () => void
  onHelp: () => void
}) {
  const allowed = canDecide ? ORDER.filter(a => detail.allowedActions?.includes(a)) : []
  const schedulable = canSchedule && ['SHORTLISTED', 'INTERVIEWING'].includes(detail.applicationStatus)
  const primary = canDecide ? primaryFor(detail.applicationStatus, allowed, schedulable) : null
  const gates = detail.requirements.filter(r => isGateOpen(r, detail))
  const secondary = allowed.filter(a => a !== primary && a !== 'REJECT')
  const final = detail.applicationStatus === 'SELECTED' || detail.applicationStatus === 'WITHDRAWN'

  const summary = (
    <p className="ap-bar-summary">
      {detail.verifiedCoverage != null ? (
        <span>
          Verified <strong className="numeric">{Math.round(detail.verifiedCoverage)}%</strong>
          <span className="ap-bar-dim"> · with claims <span className="numeric">{Math.round(detail.claimedCoverage ?? detail.verifiedCoverage)}%</span></span>
        </span>
      ) : (
        <span className="ap-bar-dim">No coverage yet</span>
      )}
      {gates.length > 0 ? (
        <span className="ap-bar-gate"><Icon icon={GateOpen} size={14} />{gates.length === 1 ? `Gate open: ${gates[0].description}` : plural(gates.length, 'gate') + ' open'}</span>
      ) : detail.rankGroup === 'RANKED' ? (
        <span className="ap-bar-ok"><Icon icon={ShieldCheck} size={14} />Gates verified</span>
      ) : null}
    </p>
  )

  if (final) {
    return (
      <div className="ap-bar" data-final={detail.applicationStatus}>
        <div className="ap-bar-inner">
          <p className="ap-bar-outcome">
            <Icon icon={detail.applicationStatus === 'SELECTED' ? CircleCheck : Info} size={16} />
            {detail.applicationStatus === 'SELECTED' ? 'Selected — handed off to your offer process.' : 'The candidate withdrew this application.'}
          </p>
        </div>
      </div>
    )
  }

  if (!canDecide) {
    return (
      <div className="ap-bar" data-readonly="">
        <div className="ap-bar-inner">
          {summary}
          <p className="ap-bar-dim ap-bar-note">HR managers make decisions. Your review note and verifications inform them.</p>
        </div>
      </div>
    )
  }

  if (allowed.length === 0 && !schedulable) return null

  return (
    <div className="ap-bar" role="region" aria-label="Decision">
      <div className="ap-bar-inner">
        {summary}
        <div className="ap-bar-actions">
          <IconButton label="Keyboard shortcuts (?)" icon={Info} onClick={onHelp} className="ap-bar-help" />
          {secondary.length > 0 || (schedulable && primary !== 'SCHEDULE') ? (
            <Menu
              label="More decisions"
              trigger={props => <Button {...props} variant="ghost" icon={Ellipsis} aria-label="More decisions">More</Button>}
              items={[
                ...(schedulable && primary !== 'SCHEDULE' ? [{ label: 'Schedule interview', icon: CalendarClock, onSelect: onSchedule }] : []),
                ...secondary.map(a => ({ label: ACTIONS[a].label, onSelect: () => onAction(a) })),
              ]}
            />
          ) : null}
          {allowed.includes('REJECT') && (
            <Button variant="danger-ghost" onClick={() => onAction('REJECT')}>Reject</Button>
          )}
          {primary && <span className="ap-bar-divider" aria-hidden="true" />}
          {primary === 'SCHEDULE' ? (
            <Button variant="primary" icon={CalendarClock} onClick={onSchedule}>Schedule interview</Button>
          ) : primary ? (
            <Button variant="primary" onClick={() => onAction(primary)}>{ACTIONS[primary].label}</Button>
          ) : null}
        </div>
      </div>
    </div>
  )
}

/** The last check before Select: coverage, every requirement's tier, scorecards and work-sample scores. */
export function SelectCheck({ detail }: { detail: ApplicantDetail }) {
  const scorecards = detail.interviews.filter(i => i.scorecard)
  const scored = detail.workSamples.filter(w => w.totalScore != null)
  return (
    <div className="ap-select-check">
      <p className="t-label">Last check</p>
      <dl className="ap-check-facts">
        <div><dt>Verified coverage</dt><dd className="numeric">{detail.verifiedCoverage != null ? `${Math.round(detail.verifiedCoverage)}%` : '—'}</dd></div>
        <div><dt>Scorecards</dt><dd>{scorecards.length ? scorecards.map(i => recommendationWord(i.scorecard!.recommendation)).join(', ') : 'None yet'}</dd></div>
        <div><dt>Work samples</dt><dd className="numeric">{scored.length ? scored.map(w => `${w.totalScore!.toFixed(1)} / 4`).join(', ') : 'None scored'}</dd></div>
      </dl>
      <ul className="ap-check-reqs">
        {orderRequirements(detail.requirements, detail).map(r => (
          <li key={r.requirementId}>
            <span>{r.description}</span>
            <EvidenceLadder tier={r.tier} audience="staff" animate={false} gateOpen={isGateOpen(r, detail)} />
          </li>
        ))}
      </ul>
    </div>
  )
}

export function recommendationWord(value: string): string {
  return ({ STRONG_NO: 'Strong no', NO: 'No', YES: 'Yes', STRONG_YES: 'Strong yes' } as Record<string, string>)[value] ?? value
}
