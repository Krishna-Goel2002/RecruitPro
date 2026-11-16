import { describe, expect, it } from 'vitest'
import { writeDraft } from '../../lib/localDraft'
import { DRAFT_MAX_AGE_MS, initialForm, newDraftKey, readNewJobDraft, toBody, validateBasics, type FormState } from './JobFormPage'
import { adoptServerIds, draftFrom, emptyDraft, requirementChecks, sameRequirements, sameRequirementSet, sortByDisplayOrder, toInput, validateDrafts } from './requirementDrafts'

const filled = (over: Partial<FormState> = {}): FormState => ({
  ...initialForm(),
  title: 'Backend Engineer', department: 'Engineering', location: 'Pune', description: 'Build and run our services.',
  ...over,
})

describe('job wizard: basics validation', () => {
  const now = Date.parse('2026-11-16T10:00:00Z')
  it('requires the visible fields and a sane pay range', () => {
    const errors = validateBasics(initialForm(), undefined, now)
    expect(Object.keys(errors).sort()).toEqual(['department', 'description', 'location', 'title'])
    expect(validateBasics(filled({ minimumSalary: '900000', maximumSalary: '500000' }), undefined, now).maximumSalary).toMatch(/at least the minimum/)
    expect(validateBasics(filled({ vacancies: '0' }), undefined, now).vacancies).toBeTruthy()
    expect(validateBasics(filled({ vacancies: '1.5' }), undefined, now).vacancies).toBeTruthy()
    expect(validateBasics(filled(), undefined, now)).toEqual({})
  })

  it('needs a future deadline, but keeps an unchanged one on edit', () => {
    expect(validateBasics(filled({ deadline: '2026-11-01T10:00' }), undefined, now).deadline).toMatch(/future/)
    const job = { deadline: new Date('2026-11-01T10:00').toISOString() } as never
    expect(validateBasics(filled({ deadline: '2026-11-01T10:00' }), job, now).deadline).toBeUndefined()
  })

  it('sends numbers and nulls, trimmed text', () => {
    const body = toBody(filled({ title: '  Backend Engineer ', minimumSalary: '', maximumSalary: '1200000', vacancies: '2' }))
    expect(body).toMatchObject({ title: 'Backend Engineer', minimumSalary: null, maximumSalary: 1200000, vacancies: 2, deadline: null })
  })
})

describe('job wizard: device draft (autosave before the first server save)', () => {
  it('is kept per user and survives a reload', () => {
    writeDraft(newDraftKey(7), filled({ title: 'Data Engineer' }))
    expect(readNewJobDraft(newDraftKey(7))?.value.title).toBe('Data Engineer')
    expect(readNewJobDraft(newDraftKey(8))).toBeNull()
  })

  it('drops stale drafts', () => {
    window.localStorage.setItem(`draft:${newDraftKey(7)}`, JSON.stringify({ value: filled(), savedAt: Date.now() - DRAFT_MAX_AGE_MS - 1000 }))
    expect(readNewJobDraft(newDraftKey(7))).toBeNull()
    expect(window.localStorage.getItem(`draft:${newDraftKey(7)}`)).toBeNull()
  })

  it('repairs corrupted or old-shape drafts instead of crashing', () => {
    window.localStorage.setItem(`draft:${newDraftKey(7)}`, JSON.stringify({ value: { title: 42, description: 'Kept', jobType: 'ASTRONAUT', minimumSalary: '₹ 5,00,000' }, savedAt: Date.now() }))
    const draft = readNewJobDraft(newDraftKey(7))
    expect(draft?.value.title).toBe('')
    expect(draft?.value.description).toBe('Kept')
    expect(draft?.value.jobType).toBe('FULL_TIME')
    expect(draft?.value.minimumSalary).toBe('500000')
    window.localStorage.setItem(`draft:${newDraftKey(7)}`, '{not json')
    expect(readNewJobDraft(newDraftKey(7))).toBeNull()
  })

  it('works when storage is blocked', () => {
    const original = Object.getOwnPropertyDescriptor(window, 'localStorage')
    Object.defineProperty(window, 'localStorage', { configurable: true, get() { throw new Error('SecurityError') } })
    try {
      expect(readNewJobDraft(newDraftKey(7))).toBeNull()
      expect(writeDraft('x', 1)).toBeNull()
    } finally {
      if (original) Object.defineProperty(window, 'localStorage', original)
    }
  })
})

describe('job wizard: requirements', () => {
  it('validates text, years and alternatives like the server', () => {
    const a = { ...emptyDraft(), description: '' }
    const b = { ...emptyDraft(), description: 'Java', minimumYears: '51' }
    const c = { ...emptyDraft(), description: 'x'.repeat(501) }
    const d = { ...emptyDraft(), description: 'Go', alternatives: ['y'.repeat(201)] }
    const errors = validateDrafts([a, b, c, d])
    expect(Object.keys(errors)).toHaveLength(4)
  })

  it('a reorder is a change again, but recognisably a pure reorder (the server stores displayOrder; no re-match)', () => {
    const one = toInput({ ...emptyDraft(), id: 1, description: 'Java' })
    const two = toInput({ ...emptyDraft(), id: 2, description: 'SQL', mandatory: false })
    expect(sameRequirements([one, two], [two, one])).toBe(false)
    expect(sameRequirementSet([one, two], [two, one])).toBe(true)
    expect(sameRequirements([one, two], [one, two])).toBe(true)
    expect(sameRequirements([one, two], [one, { ...two, mandatory: true }])).toBe(false)
    expect(sameRequirementSet([one, two], [{ ...two, mandatory: true }, one])).toBe(false)
    expect(sameRequirements([one], [one, two])).toBe(false)
  })

  it('lists requirements in displayOrder, keeping the server order when it is missing', () => {
    expect(sortByDisplayOrder([{ id: 3, displayOrder: 1 }, { id: 9, displayOrder: 0 }, { id: 1, displayOrder: 2 }]).map(r => r.id)).toEqual([9, 3, 1])
    expect(sortByDisplayOrder([{ id: 3 }, { id: 1 }]).map(r => r.id)).toEqual([3, 1])
  })

  it('new rows take the ids the server created, so the next save updates them in place', () => {
    const existing = draftFrom({ id: 5, type: 'SKILL', description: 'Java', mandatory: true, knockout: false, alternatives: [] } as never)
    const added = { ...emptyDraft(), type: 'SKILL' as const, description: 'Kafka ' }
    const next = adoptServerIds([existing, added], [{ id: 5, type: 'SKILL', description: 'Java' }, { id: 9, type: 'SKILL', description: 'Kafka' }])
    expect(next.map(d => d.id)).toEqual([5, 9])
    expect(adoptServerIds([existing], [{ id: 5, type: 'SKILL', description: 'Java' }])).toEqual([existing])
  })

  it('caps compiler alternatives at the server limit', () => {
    const draft = draftFrom({ type: 'SKILL', description: 'Java', mandatory: true, alternatives: Array.from({ length: 14 }, (_, i) => `alt ${i}`) } as never)
    expect(draft.alternatives).toHaveLength(10)
  })

  it('review checks flag missing requirements, long experience on junior roles and a past deadline', () => {
    const checks = requirementChecks([{ ...emptyDraft(), description: 'Java', minimumYears: '12' }], 'ENTRY', '2020-01-01T00:00:00Z', 1)
    const ids = checks.filter(c => !c.ok).map(c => c.id)
    expect(ids).toEqual(expect.arrayContaining(['years', 'wording', 'deadline']))
    expect(requirementChecks([], 'SENIOR', null, 0).find(c => c.id === 'reqs')?.ok).toBe(false)
  })
})
