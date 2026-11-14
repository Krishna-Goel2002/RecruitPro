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
  })
  return errors
}
