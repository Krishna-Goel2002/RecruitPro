import { useId, useRef, useState, type DragEvent, type KeyboardEvent } from 'react'
import { PROFICIENCIES, REQUIREMENT_TYPES, RequirementType } from '../../api/types'
import { Button, Checkbox, Field, IconButton, Input, Select, Tag, Tooltip, useToast } from '../../design/components'
import { plural } from '../../design/lib/format'
import { useFlip } from '../../design/motion'
import { ArrowDown, ArrowUp, ChevronDown, Icon, Plus, Trash2 } from '../../design/icons'
import { humanize } from '../../lib/format'
import { emptyDraft, gateAllowed, MAX_REQUIREMENTS, RequirementDraft } from './requirementDrafts'

const TYPE_LABEL: Record<RequirementType, string> = { SKILL: 'Skill', EXPERIENCE: 'Experience', EDUCATION: 'Education', CERTIFICATION: 'Certification', OTHER: 'Other' }

/** Six dots: the drag handle (not in the curated icon set, drawn here). */
function Grip() {
  return (
    <svg width="10" height="16" viewBox="0 0 10 16" aria-hidden="true" focusable="false">
      {[2, 8, 14].flatMap(y => [2, 8].map(x => <circle key={`${x}-${y}`} cx={x} cy={y} r="1.25" fill="currentColor" />))}
    </svg>
  )
}

/**
 * Compact requirement rows: drag handle · type · text · Must-have · Gate · "More" (years, proficiency, alternatives)
 * · remove with Undo. Reorder by dragging, or Alt+↑/↓ inside a row; rows glide to their new place (FLIP).
 */
export function RequirementsEditor({ drafts, onChange, errors }: {
  drafts: RequirementDraft[]
  onChange: (drafts: RequirementDraft[]) => void
  errors: Record<string, string>
}) {
  const { toast } = useToast()
  const listRef = useRef<HTMLOListElement>(null)
  const [open, setOpen] = useState<Set<string>>(new Set())
  const [dragging, setDragging] = useState<string | null>(null)
  useFlip(listRef, drafts.map(d => d.key).join('|'))

  const update = (key: string, values: Partial<RequirementDraft>) => onChange(drafts.map(draft => (draft.key === key ? { ...draft, ...values } : draft)))
  const move = (index: number, delta: number, refocus?: HTMLElement | null) => {
    const target = index + delta
    if (target < 0 || target >= drafts.length) return
    const next = [...drafts]
    const [item] = next.splice(index, 1)
    next.splice(target, 0, item)
    onChange(next)
    if (refocus) window.requestAnimationFrame(() => refocus.focus())
  }
  // Undo must restore into the CURRENT list: edits made while the toast was up are kept.
  const latest = useRef(drafts)
  latest.current = drafts
  const remove = (index: number) => {
    const removed = drafts[index]
    onChange(drafts.filter((_, i) => i !== index))
    toast({
      tone: 'info',
      title: `Removed “${removed.description.trim() || 'untitled requirement'}”`,
      duration: 6000,
      action: {
        label: 'Undo',
        onClick: () => {
          const now = latest.current.filter(d => d.key !== removed.key)
          onChange(insertAt(now, Math.min(index, now.length), removed))
        },
      },
    })
  }
  const add = () => {
    const draft = emptyDraft()
    onChange([...drafts, draft])
    window.setTimeout(() => document.getElementById(`req-text-${draft.key}`)?.focus(), 0)
  }

  const onDragOver = (event: DragEvent<HTMLLIElement>, overKey: string) => {
    if (!dragging || dragging === overKey) return
    event.preventDefault()
    const from = drafts.findIndex(d => d.key === dragging)
    const to = drafts.findIndex(d => d.key === overKey)
    if (from < 0 || to < 0) return
    const next = [...drafts]
    const [item] = next.splice(from, 1)
    next.splice(to, 0, item)
    onChange(next)
  }

  const must = drafts.filter(d => d.mandatory).length
  const gates = drafts.filter(d => d.mandatory && d.knockout && gateAllowed(d)).length

  return (
    <div className="hra-req-editor">
      {drafts.length > 0 && (
        <p className="hra-req-summary numeric" aria-live="polite">
          {plural(drafts.length, 'requirement')} · {plural(must, 'must-have')} · {plural(gates, 'gate')}
        </p>
      )}
      {drafts.length === 0 ? (
        <p className="hra-req-empty">No requirements yet. Add them by hand, or draft them from the description.</p>
      ) : (
        <ol className="hra-req-list" ref={listRef}>
          {drafts.map((draft, index) => (
            <RequirementRow
              key={draft.key}
              draft={draft}
              index={index}
              total={drafts.length}
              error={errors[draft.key]}
              expanded={open.has(draft.key)}
              dragging={dragging === draft.key}
              onToggle={() => setOpen(current => {
                const next = new Set(current)
                if (next.has(draft.key)) next.delete(draft.key)
                else next.add(draft.key)
                return next
              })}
              onUpdate={values => update(draft.key, values)}
              onRemove={() => remove(index)}
              onMove={(delta, el) => move(index, delta, el)}
              onDragStart={() => setDragging(draft.key)}
              onDragEnd={() => setDragging(null)}
              onDragOver={event => onDragOver(event, draft.key)}
            />
          ))}
        </ol>
      )}
      <Button icon={Plus} onClick={add} disabled={drafts.length >= MAX_REQUIREMENTS}>Add requirement</Button>
    </div>
  )
}

function insertAt<T>(list: T[], index: number, item: T): T[] {
  return [...list.slice(0, index), item, ...list.slice(index)]
}

function RequirementRow({ draft, index, total, error, expanded, dragging, onToggle, onUpdate, onRemove, onMove, onDragStart, onDragEnd, onDragOver }: {
  draft: RequirementDraft
  index: number
  total: number
  error?: string
  expanded: boolean
  dragging: boolean
  onToggle: () => void
  onUpdate: (values: Partial<RequirementDraft>) => void
  onRemove: () => void
  onMove: (delta: number, refocus?: HTMLElement | null) => void
  onDragStart: () => void
  onDragEnd: () => void
  onDragOver: (event: DragEvent<HTMLLIElement>) => void
}) {
  const id = useId()
  const label = draft.description.trim() || `requirement ${index + 1}`
  const gateOk = gateAllowed(draft)
  const extras = [
    draft.minimumYears !== '' ? `${draft.minimumYears}+ years` : null,
    draft.proficiency ? humanize(draft.proficiency) : null,
    draft.alternatives.length ? `${plural(draft.alternatives.length, 'alternative')}` : null,
  ].filter(Boolean)

  const onKeyDown = (event: KeyboardEvent<HTMLLIElement>) => {
    if (!event.altKey || (event.key !== 'ArrowUp' && event.key !== 'ArrowDown')) return
    // Alt+↓ on a <select> opens its list (Windows); leave it alone.
    if ((event.target as HTMLElement).tagName === 'SELECT') return
    event.preventDefault()
    onMove(event.key === 'ArrowUp' ? -1 : 1, document.activeElement as HTMLElement)
  }

  return (
    <li
      className="hra-req-row"
      data-flip-id={draft.key}
      data-dragging={dragging || undefined}
      data-invalid={error ? true : undefined}
      aria-label={`Requirement ${index + 1} of ${total}`}
      onKeyDown={onKeyDown}
      onDragOver={onDragOver}
      onDrop={event => event.preventDefault()}
    >
      <div className="hra-req-main">
        <span
          className="hra-req-grip"
          draggable
          onDragStart={event => {
            event.dataTransfer.effectAllowed = 'move'
            event.dataTransfer.setData('text/plain', draft.key)
            onDragStart()
          }}
          onDragEnd={onDragEnd}
          title="Drag to reorder (or Alt+↑/↓)"
          aria-hidden="true"
        >
          <Grip />
        </span>
        <Select
          aria-label={`Type of ${label}`}
          controlSize="sm"
          className="hra-req-type"
          value={draft.type}
          onChange={event => {
            const type = event.target.value as RequirementType
            onUpdate({ type, knockout: type === 'OTHER' ? false : draft.knockout })
          }}
        >
          {REQUIREMENT_TYPES.map(type => <option key={type} value={type}>{TYPE_LABEL[type]}</option>)}
        </Select>
        <Input
          id={`req-text-${draft.key}`}
          aria-label={`Requirement ${index + 1}`}
          className="hra-req-text"
          value={draft.description}
          maxLength={500}
          placeholder="e.g. Java 17 and Spring Boot"
          onChange={event => onUpdate({ description: event.target.value })}
          aria-invalid={error ? true : undefined}
          aria-describedby={error ? `${id}-error` : undefined}
          controlSize="sm"
        />
        <span className="hra-req-flags">
          <Checkbox
            label="Must-have"
            aria-label={`Must-have: ${label}`}
            checked={draft.mandatory}
            onChange={event => onUpdate({ mandatory: event.target.checked, knockout: event.target.checked ? draft.knockout : false })}
          />
          <Tooltip
            content={gateOk
              ? 'Holds an applicant in “Must-haves not yet verified” until this is verified. Never rejects anyone.'
              : draft.type === 'OTHER' ? '“Other” requirements are never scored, so they cannot be a gate.' : 'Only a must-have can be a gate.'}
            labelOnly
          >
            <Checkbox
              label="Gate"
              checked={gateOk && draft.knockout}
              disabled={!gateOk}
              onChange={event => onUpdate({ knockout: event.target.checked })}
              aria-label={`Gate: ${label}`}
            />
          </Tooltip>
        </span>
        <span className="hra-req-tools">
          <button type="button" className="hra-req-more" aria-expanded={expanded} aria-controls={`${id}-more`} onClick={onToggle}>
            <span>{extras.length ? extras.join(' · ') : 'More'}</span>
            <Icon icon={ChevronDown} size={14} className="hra-chevron" />
          </button>
          <IconButton label={`Remove ${label}`} icon={Trash2} size="sm" onClick={onRemove} />
        </span>
      </div>
      {error && <p className="ui-field-error hra-req-error" id={`${id}-error`}>{error}</p>}
      {draft.type === 'OTHER' && draft.mandatory && (
        <p className="hra-req-note">“Other” requirements are reviewed by people. They are not scored, so they do not count towards coverage.</p>
      )}
      <div className="hra-expand" data-open={expanded || undefined} id={`${id}-more`}>
        <div className="hra-expand-inner">
          {expanded && (
            <div className="hra-req-details">
              <Field label="Minimum years" optional>
                <Input type="number" min={0} max={50} step={1} inputMode="numeric" value={draft.minimumYears} placeholder="Any" onChange={event => onUpdate({ minimumYears: event.target.value })} controlSize="sm" />
              </Field>
              <Field label="Proficiency" optional hint="Shown to reviewers; not used in matching yet.">
                <Select value={draft.proficiency} controlSize="sm" onChange={event => onUpdate({ proficiency: event.target.value as RequirementDraft['proficiency'] })}>
                  <option value="">Not specified</option>
                  {PROFICIENCIES.map(level => <option key={level} value={level}>{humanize(level)}</option>)}
                </Select>
              </Field>
              <AlternativesInput values={draft.alternatives} onChange={alternatives => onUpdate({ alternatives })} />
              <div className="hra-req-move">
                <IconButton label={`Move ${label} up`} icon={ArrowUp} size="sm" disabled={index === 0} onClick={e => onMove(-1, e.currentTarget)} />
                <IconButton label={`Move ${label} down`} icon={ArrowDown} size="sm" disabled={index === total - 1} onClick={e => onMove(1, e.currentTarget)} />
              </div>
            </div>
          )}
        </div>
      </div>
    </li>
  )
}

/** Alternatives ("any one satisfies it"): Enter or comma adds; Backspace on an empty field removes the last. */
function AlternativesInput({ values, onChange }: { values: string[]; onChange: (values: string[]) => void }) {
  const [text, setText] = useState('')
  const id = useId()
  const add = () => {
    const value = text.trim().replace(/,$/, '').trim()
    if (!value) return
    if (!values.some(v => v.toLowerCase() === value.toLowerCase()) && values.length < 10) onChange([...values, value])
    setText('')
  }
  return (
    <div className="hra-alts">
      <Field label="Also accepted" optional hint={values.length >= 10 ? 'Up to 10 alternatives.' : 'Any one of these satisfies the requirement. Press Enter to add.'}>
        <Input
          value={text}
          maxLength={200}
          controlSize="sm"
          placeholder={values.length >= 10 ? 'Limit reached' : 'e.g. Kotlin'}
          disabled={values.length >= 10}
          onChange={event => setText(event.target.value)}
          onBlur={add}
          onKeyDown={event => {
            if (event.key === 'Enter' || event.key === ',') {
              event.preventDefault()
              add()
            } else if (event.key === 'Backspace' && !text && values.length) onChange(values.slice(0, -1))
          }}
        />
      </Field>
      {values.length > 0 && (
        <ul className="hra-alt-list" aria-label={`Also accepted: ${values.length}`} id={`${id}-list`}>
          {values.map(value => <li key={value}><Tag onRemove={() => onChange(values.filter(v => v !== value))} removeLabel={`Remove ${value}`}>{value}</Tag></li>)}
        </ul>
      )}
    </div>
  )
}
