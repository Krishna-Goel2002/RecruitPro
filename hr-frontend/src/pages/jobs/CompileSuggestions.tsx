import { useEffect, useState } from 'react'
import type { CompileResult } from '../../api/types'
import { Badge, Button, Checkbox, ReqMarker, Skeleton } from '../../design/components'
import { plural } from '../../design/lib/format'
import { humanize, WARNING_LABELS } from '../../lib/format'

/**
 * Suggestions from POST /jobs/compile-requirements. All pre-selected; each shows type, text, must-have / gate /
 * years / proficiency, alternatives and the rationale quote. Wording warnings (age- or gender-coded, degree inflation,
 * exclusionary, vague, excessive experience) sit above them with the suggested rewrite. Nothing is saved until the
 * suggestions are added to the list and the job is saved.
 */
export function CompileSuggestions({ result, hasExisting, onAccept, onDismiss }: {
  result: CompileResult
  hasExisting: boolean
  onAccept: (indexes: number[], mode: 'append' | 'replace') => void
  onDismiss: () => void
}) {
  const [selected, setSelected] = useState<Set<number>>(() => new Set(result.requirements.map((_, index) => index)))
  useEffect(() => { setSelected(new Set(result.requirements.map((_, index) => index))) }, [result])
  const toggle = (index: number) => setSelected(current => {
    const next = new Set(current)
    if (next.has(index)) next.delete(index)
    else next.add(index)
    return next
  })
  const chosen = [...selected].sort((a, b) => a - b)
  const all = chosen.length === result.requirements.length

  return (
    <section className="hra-compile" aria-labelledby="compile-h">
      <header className="hra-compile-head">
        <div>
          <h3 id="compile-h" className="t-h3">Suggested from your description</h3>
          <p className="subtle">
            {result.engine === 'llm' ? 'Drafted by the assistant and checked against your text' : 'Drafted by the rules engine'}. Each suggestion quotes the sentence it came from.
          </p>
        </div>
        <Button size="sm" variant="ghost" onClick={onDismiss}>Dismiss</Button>
      </header>

      {result.warnings.length > 0 && (
        <div className="hra-wording" role="note" aria-labelledby="wording-h">
          <h4 id="wording-h" className="t-label">Wording to reconsider <span className="subtle numeric">({result.warnings.length})</span></h4>
          <ul>
            {result.warnings.map((warning, index) => (
              <li key={`${warning.phrase}-${index}`}>
                <Badge tone="warning">{WARNING_LABELS[warning.kind] ?? humanize(warning.kind)}</Badge>
                <span className="hra-wording-phrase">“{warning.phrase}”</span>
                <span className="hra-wording-fix">{warning.suggestion}</span>
              </li>
            ))}
          </ul>
        </div>
      )}

      {result.requirements.length === 0 ? (
        <p className="subtle">No requirements could be found in this description. Add them by hand below.</p>
      ) : (
        <>
          <div className="hra-compile-bar">
            <Checkbox
              label={all ? 'All selected' : `${chosen.length} of ${result.requirements.length} selected`}
              checked={all}
              indeterminate={!all && chosen.length > 0}
              onChange={() => setSelected(all ? new Set() : new Set(result.requirements.map((_, i) => i)))}
            />
          </div>
          <ul className="hra-suggestions">
            {result.requirements.map((suggestion, index) => (
              <li key={`${suggestion.description}-${index}`} className="hra-suggestion" data-selected={selected.has(index) || undefined} style={{ ['--i' as string]: Math.min(index, 7) }}>
                <Checkbox
                  aria-label={`Use: ${suggestion.description}`}
                  checked={selected.has(index)}
                  onChange={() => toggle(index)}
                />
                <div className="hra-suggestion-body">
                  <p className="hra-suggestion-title">
                    <span className="hra-suggestion-type">{humanize(suggestion.type)}</span>
                    <strong>{suggestion.description}</strong>
                  </p>
                  <p className="hra-suggestion-tags">
                    <ReqMarker kind={suggestion.mandatory ? 'must' : 'nice'} />
                    {suggestion.mandatory && suggestion.knockout && <ReqMarker kind="gate" />}
                    {suggestion.minimumYears != null && <span>{plural(suggestion.minimumYears, 'year')} minimum</span>}
                    {suggestion.proficiency && <span>{humanize(suggestion.proficiency)}</span>}
                    {suggestion.alternatives.length > 0 && <span>Also accepted: {suggestion.alternatives.join(', ')}</span>}
                  </p>
                  {suggestion.rationale && <p className="hra-suggestion-why">{suggestion.rationale}</p>}
                </div>
              </li>
            ))}
          </ul>
          <div className="hra-compile-actions">
            {hasExisting && <Button disabled={!chosen.length} onClick={() => onAccept(chosen, 'replace')}>Replace my list</Button>}
            <Button variant="primary" disabled={!chosen.length} onClick={() => onAccept(chosen, 'append')}>
              {hasExisting ? `Add ${chosen.length} to my list` : `Use ${chosen.length} suggestion${chosen.length === 1 ? '' : 's'}`}
            </Button>
          </div>
        </>
      )}
    </section>
  )
}

/** Shown while the compiler reads the description. */
export function CompileSkeleton() {
  return (
    <section className="hra-compile" aria-busy="true">
      <p className="hra-compile-reading" role="status">Reading your description…</p>
      {Array.from({ length: 4 }, (_, i) => (
        <div key={i} className="hra-suggestion-skel">
          <Skeleton width={16} height={16} radius={3} />
          <div className="stack-2" style={{ flex: 1 }}>
            <Skeleton width={`${[62, 48, 70, 54][i]}%`} height={12} />
            <Skeleton width={`${[34, 40, 28, 36][i]}%`} height={10} />
          </div>
        </div>
      ))}
    </section>
  )
}
