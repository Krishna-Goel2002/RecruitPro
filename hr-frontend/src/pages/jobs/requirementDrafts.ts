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
    alternatives: requirement.alternatives ?? [],
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

/** Order-sensitive comparison of what would be sent, to skip no-op requirement saves (and needless re-matching). */
export function sameRequirements(a: RequirementInput[], b: RequirementInput[]): boolean {
  return JSON.stringify(a.map(normalize)) === JSON.stringify(b.map(normalize))
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
