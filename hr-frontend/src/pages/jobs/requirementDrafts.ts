import type { Proficiency, Requirement, RequirementInput, RequirementType } from '../../api/types'

/** A requirement row being edited; `key` is a stable React key for rows without a server id. */
export type RequirementDraft = {
  key: string
  /** Server id of an existing requirement. Sent with the bulk replace so the backend updates the row in place and
   *  keeps links from work-sample rubrics, interview kits and match evidence (WP-BE-B bulk PUT semantics). */
  id?: number
  type: RequirementType
  description: string
  mandatory: boolean
  knockout: boolean
  minimumYears: string
  alternatives: string[]
  proficiency: Proficiency | ''
}

let counter = 0
export function draftKey() {
  counter += 1
  return `req-${Date.now().toString(36)}-${counter}`
}

export function emptyDraft(): RequirementDraft {
  return { key: draftKey(), type: 'SKILL', description: '', mandatory: true, knockout: false, minimumYears: '', alternatives: [], proficiency: '' }
}

export function draftFrom(requirement: Partial<Requirement> & RequirementInput): RequirementDraft {
  return {
    key: requirement.id != null ? `id-${requirement.id}` : draftKey(),
    id: requirement.id ?? undefined,
    type: requirement.type,
    description: requirement.description,
    mandatory: requirement.mandatory,
    // Older backends omit knockout; the plan's default is knockout = mandatory.
    knockout: requirement.knockout ?? requirement.mandatory,
    minimumYears: requirement.minimumYears == null ? '' : String(requirement.minimumYears),
    // The server accepts at most 10 alternatives of up to 200 characters (RequirementInput); compiler output can exceed it.
    alternatives: (requirement.alternatives ?? []).filter(alt => typeof alt === 'string' && alt.trim()).slice(0, 10),
    proficiency: requirement.proficiency ?? '',
  }
}

export function toInput(draft: RequirementDraft): RequirementInput {
  return {
    ...(draft.id != null ? { id: draft.id } : {}),
    type: draft.type,
    description: draft.description.trim(),
    mandatory: draft.mandatory,
    knockout: draft.mandatory && draft.knockout,
    minimumYears: draft.minimumYears === '' ? null : Number(draft.minimumYears),
    alternatives: draft.alternatives,
    proficiency: draft.proficiency === '' ? null : draft.proficiency,
  }
}

/**
 * Comparison of what would be sent, to skip no-op requirement saves. Order-sensitive (integration pass): the server
 * stores the order (`displayOrder`), and a bulk replace that only reorders saves just the order (no new requirements
 * version, no audit row, no re-match), so a reorder is a real but cheap change.
 */
export function sameRequirements(a: RequirementInput[], b: RequirementInput[]): boolean {
  if (a.length !== b.length) return false
  const key = (input: RequirementInput) => JSON.stringify(normalize(input))
  return a.every((input, index) => key(input) === key(b[index]))
}

/** Same requirements, ignoring order: when true but sameRequirements is false, the change is a pure reorder. */
export function sameRequirementSet(a: RequirementInput[], b: RequirementInput[]): boolean {
  if (a.length !== b.length) return false
  const key = (input: RequirementInput) => JSON.stringify(normalize(input))
  return JSON.stringify(a.map(key).sort()) === JSON.stringify(b.map(key).sort())
}

/** Requirements in the order HR set (`displayOrder`, then id); servers without the field keep their order. */
export function sortByDisplayOrder<T extends { id?: number; displayOrder?: number | null }>(requirements: readonly T[]): T[] {
  return requirements
    .map((requirement, index) => ({ requirement, index }))
    .sort((x, y) => {
      const a = x.requirement.displayOrder
      const b = y.requirement.displayOrder
      if (a != null && b != null && a !== b) return a - b
      return x.index - y.index
    })
    .map(entry => entry.requirement)
}

/**
 * After a bulk replace the server has created rows for drafts that had no id. Give those drafts their new ids (matched
 * on type + text, each server row used once), so the next save updates them in place instead of deleting and
 * re-creating them (which would cut links from work-sample rubrics and interview kits).
 */
export function adoptServerIds(drafts: RequirementDraft[], saved: Pick<Requirement, 'id' | 'type' | 'description'>[]): RequirementDraft[] {
  const taken = new Set(drafts.filter(d => d.id != null).map(d => d.id as number))
  const free = saved.filter(r => !taken.has(r.id))
  let changed = false
  const next = drafts.map(draft => {
    if (draft.id != null) return draft
    const index = free.findIndex(r => r.type === draft.type && r.description.trim() === draft.description.trim())
    if (index < 0) return draft
    const [match] = free.splice(index, 1)
    changed = true
    return { ...draft, id: match.id }
  })
  return changed ? next : drafts
}

function normalize(input: RequirementInput) {
  return [input.id ?? null, input.type, input.description.trim(), input.mandatory, input.mandatory && (input.knockout ?? input.mandatory), input.minimumYears ?? null, [...(input.alternatives ?? [])], input.proficiency ?? null]
}

export function validateDrafts(drafts: RequirementDraft[]): Record<string, string> {
  const errors: Record<string, string> = {}
  drafts.forEach(draft => {
    const description = draft.description.trim()
    if (!description) errors[draft.key] = 'Describe the requirement or remove the row.'
    else if (description.length > 500) errors[draft.key] = 'Use 500 characters or fewer.'
    else if (draft.minimumYears !== '') {
      const years = Number(draft.minimumYears)
      if (!Number.isInteger(years) || years < 0 || years > 50) errors[draft.key] = 'Minimum years must be a whole number from 0 to 50.'
    }
    if (!errors[draft.key] && draft.alternatives.some(alt => alt.length > 200)) errors[draft.key] = 'Keep each alternative under 200 characters.'
  })
  return errors
}

export const MAX_REQUIREMENTS = 100

/** A gate on an "Other" requirement can never be verified (the engine never scores OTHER): it is switched off. */
export function gateAllowed(draft: Pick<RequirementDraft, 'mandatory' | 'type'>): boolean {
  return draft.mandatory && draft.type !== 'OTHER'
}

export type Check = { id: string; ok: boolean; text: string; step: 'basics' | 'requirements' }

/** Pre-publish checks shown on the review step (each links back to the step that fixes it). */
export function requirementChecks(drafts: RequirementDraft[], experienceLevel: string, deadline: string | null, warnings: number): Check[] {
  const scored = drafts.filter(d => d.type !== 'OTHER')
  const gates = drafts.filter(d => d.mandatory && d.knockout && gateAllowed(d))
  const tooMany = drafts.filter(d => d.minimumYears !== '' && Number(d.minimumYears) > 10)
  const juniorRole = experienceLevel === 'ENTRY' || experienceLevel === 'MID'
  const checks: Check[] = [
    {
      id: 'reqs',
      ok: scored.length > 0,
      step: 'requirements',
      text: scored.length > 0 ? `${scored.length} scored requirement${scored.length === 1 ? '' : 's'}` : 'No scored requirements: applicants cannot be ranked until you add some.',
    },
    {
      id: 'must',
      ok: drafts.some(d => d.mandatory),
      step: 'requirements',
      text: drafts.some(d => d.mandatory) ? 'At least one must-have' : 'No must-haves: every requirement counts the same.',
    },
    {
      id: 'gates',
      ok: true,
      step: 'requirements',
      text: gates.length ? `${gates.length} ranking gate${gates.length === 1 ? '' : 's'}: applicants stay in “Must-haves not yet verified” until each is verified. Nobody is rejected automatically.` : 'No ranking gates: everyone with a match result is ranked.',
    },
  ]
  if (juniorRole && tooMany.length) {
    checks.push({ id: 'years', ok: false, step: 'requirements', text: `More than 10 years asked on an ${experienceLevel === 'ENTRY' ? 'entry-level' : 'mid-level'} role (${tooMany.map(d => d.description || 'untitled').join(', ')}). This often excludes good candidates.` })
  }
  if (warnings > 0) checks.push({ id: 'wording', ok: false, step: 'basics', text: `${warnings} wording suggestion${warnings === 1 ? '' : 's'} from the description check are still open.` })
  if (deadline && new Date(deadline).getTime() <= Date.now()) checks.push({ id: 'deadline', ok: false, step: 'basics', text: 'The deadline is in the past. Publishing needs a future deadline.' })
  return checks
}
