import { useId } from 'react'
import { PROFICIENCIES, REQUIREMENT_TYPES, RequirementType } from '../../api/types'
import { ChipsInput } from '../../components/ChipsInput'
import { CloseIcon, PlusIcon } from '../../components/Icons'
import { humanize } from '../../lib/format'
import { emptyDraft, RequirementDraft } from './requirementDrafts'

/** Requirement rows: type, description, alternatives chips, must-have and ranking-gate toggles, minimum years, proficiency. */
export function RequirementsEditor({ drafts, onChange, errors }: {
  drafts: RequirementDraft[]
  onChange: (drafts: RequirementDraft[]) => void
  errors: Record<string, string>
}) {
  const update = (key: string, values: Partial<RequirementDraft>) =>
    onChange(drafts.map(draft => (draft.key === key ? { ...draft, ...values } : draft)))
  const remove = (key: string) => onChange(drafts.filter(draft => draft.key !== key))
  const move = (index: number, delta: number) => {
    const target = index + delta
    if (target < 0 || target >= drafts.length) return
    const next = [...drafts]
    const [item] = next.splice(index, 1)
    next.splice(target, 0, item)
    onChange(next)
  }

  return (
    <div className="requirement-rows">
      {drafts.length === 0 ? (
        <div className="requirement-empty">No requirements yet. Add them by hand or draft them from the description.</div>
      ) : (
        <ol className="requirement-row-list">
          {drafts.map((draft, index) => (
            <RequirementRow
              key={draft.key}
              index={index}
              total={drafts.length}
              draft={draft}
              error={errors[draft.key]}
              onUpdate={values => update(draft.key, values)}
              onRemove={() => remove(draft.key)}
              onMove={delta => move(index, delta)}
            />
          ))}
        </ol>
      )}
      <button type="button" className="outline-button" onClick={() => onChange([...drafts, emptyDraft()])}><PlusIcon /> Add requirement</button>
    </div>
  )
}

function RequirementRow({ draft, index, total, error, onUpdate, onRemove, onMove }: {
  draft: RequirementDraft
  index: number
  total: number
  error?: string
  onUpdate: (values: Partial<RequirementDraft>) => void
  onRemove: () => void
  onMove: (delta: number) => void
}) {
  const id = useId()
  const label = draft.description.trim() || `requirement ${index + 1}`
  return (
    <li className="req-row" aria-label={`Requirement ${index + 1}`}>
      <div className="req-row-main">
        <label className="req-field req-type" htmlFor={`${id}-type`}>
          <span>Type</span>
          <select id={`${id}-type`} value={draft.type} onChange={event => onUpdate({ type: event.target.value as RequirementType })}>
            {REQUIREMENT_TYPES.map(type => <option key={type} value={type}>{humanize(type)}</option>)}
          </select>
        </label>
        <label className="req-field req-description" htmlFor={`${id}-description`}>
          <span>Requirement</span>
          <input
            id={`${id}-description`}
            value={draft.description}
            maxLength={500}
            placeholder="e.g. GST compliance, React, B.Com"
            onChange={event => onUpdate({ description: event.target.value })}
            aria-invalid={Boolean(error)}
            aria-describedby={error ? `${id}-error` : undefined}
          />
        </label>
        <div className="req-row-tools">
          <button type="button" className="icon-button" onClick={() => onMove(-1)} disabled={index === 0} aria-label={`Move ${label} up`}>↑</button>
          <button type="button" className="icon-button" onClick={() => onMove(1)} disabled={index === total - 1} aria-label={`Move ${label} down`}>↓</button>
          <button type="button" className="icon-button danger" onClick={onRemove} aria-label={`Remove ${label}`}><CloseIcon /></button>
        </div>
      </div>
      {error && <small className="field-error" id={`${id}-error`}>{error}</small>}
      <div className="req-row-details">
        <ChipsInput label="Alternatives (any one satisfies it)" values={draft.alternatives} onChange={alternatives => onUpdate({ alternatives })} placeholder="Type and press Enter" />
        <label className="req-field req-years" htmlFor={`${id}-years`}>
          <span>Minimum years</span>
          <input id={`${id}-years`} type="number" min={0} max={50} step={1} inputMode="numeric" value={draft.minimumYears} placeholder="Any" onChange={event => onUpdate({ minimumYears: event.target.value })} />
        </label>
        <label className="req-field req-proficiency" htmlFor={`${id}-proficiency`}>
          <span>Proficiency</span>
          <select id={`${id}-proficiency`} value={draft.proficiency} onChange={event => onUpdate({ proficiency: event.target.value as RequirementDraft['proficiency'] })}>
            <option value="">Not specified</option>
            {PROFICIENCIES.map(level => <option key={level} value={level}>{humanize(level)}</option>)}
          </select>
        </label>
        <div className="req-checks">
          <label className="check-label">
            <input type="checkbox" checked={draft.mandatory} onChange={event => onUpdate({ mandatory: event.target.checked, knockout: event.target.checked ? draft.knockout : false })} />
            Must-have
          </label>
          <label className={`check-label${draft.mandatory ? '' : ' disabled'}`} title="Applicants are ranked only after this must-have is verified. It never rejects anyone automatically.">
            <input type="checkbox" checked={draft.mandatory && draft.knockout} disabled={!draft.mandatory} onChange={event => onUpdate({ knockout: event.target.checked })} aria-describedby={`${id}-gate-hint`} />
            Ranking gate
          </label>
          <small className="sr-only" id={`${id}-gate-hint`}>Applicants are ranked only after this must-have is verified. It never rejects anyone automatically.</small>
        </div>
      </div>
    </li>
  )
}
