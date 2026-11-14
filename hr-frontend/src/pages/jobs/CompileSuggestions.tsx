import { useEffect, useState } from 'react'
import type { CompileResult } from '../../api/types'
import { humanize, WARNING_LABELS } from '../../lib/format'
import { RequirementTags } from '../../components/Badges'

/**
 * Suggestions from POST /jobs/compile-requirements: each with its rationale, all pre-selected; wording warnings
 * (age/gender-coded, degree inflation, excessive experience, vague…) with a suggested rewrite. HR selects and accepts.
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

  return (
    <section className="compile-panel" aria-labelledby="compile-heading">
      <div className="compile-head">
        <div>
          <h3 id="compile-heading">Suggested requirements</h3>
          <p>
            Drafted by the {result.engine === 'llm' ? 'language-model assistant (checked against your text)' : 'rules engine'} from your description.
            Review each one; nothing is saved until you accept and save the job.
          </p>
        </div>
        <button type="button" className="outline-button" onClick={onDismiss}>Dismiss</button>
      </div>

      {result.warnings.length > 0 && (
        <div className="wording-warnings" role="note" aria-label="Wording warnings">
          <h4>Wording to reconsider ({result.warnings.length})</h4>
          <ul>
            {result.warnings.map((warning, index) => (
              <li key={`${warning.phrase}-${index}`}>
                <span className={`warning-kind kind-${warning.kind.toLowerCase()}`}>{WARNING_LABELS[warning.kind] ?? humanize(warning.kind)}</span>
                <span className="warning-phrase">“{warning.phrase}”</span>
                <span className="warning-suggestion">{warning.suggestion}</span>
              </li>
            ))}
          </ul>
        </div>
      )}

      {result.requirements.length === 0 ? (
        <p className="muted-copy">No requirements could be drafted from this description. Add them by hand.</p>
      ) : (
        <fieldset className="suggestion-list">
          <legend className="sr-only">Select the suggestions to accept</legend>
          {result.requirements.map((suggestion, index) => (
            <label key={`${suggestion.description}-${index}`} className={`suggestion${selected.has(index) ? ' selected' : ''}`}>
              <input type="checkbox" checked={selected.has(index)} onChange={() => toggle(index)} />
              <span className="suggestion-body">
                <span className="suggestion-title">
                  <span className="req-type-label">{humanize(suggestion.type)}</span>
                  <strong>{suggestion.description}</strong>
                  <RequirementTags mandatory={suggestion.mandatory} knockout={suggestion.knockout} minimumYears={suggestion.minimumYears} />
                  {suggestion.proficiency && <span className="req-tag">{humanize(suggestion.proficiency)}</span>}
                </span>
                {suggestion.alternatives.length > 0 && <span className="suggestion-alternatives">Alternatives: {suggestion.alternatives.join(', ')}</span>}
                <span className="suggestion-rationale">{suggestion.rationale}</span>
              </span>
            </label>
          ))}
        </fieldset>
      )}

      {result.requirements.length > 0 && (
        <div className="compile-actions">
          <span>{chosen.length} of {result.requirements.length} selected</span>
          <div>
            {hasExisting && (
              <button type="button" className="outline-button" disabled={chosen.length === 0} onClick={() => onAccept(chosen, 'replace')}>Replace current requirements</button>
            )}
            <button type="button" className="primary-button workspace-primary" disabled={chosen.length === 0} onClick={() => onAccept(chosen, 'append')}>
              {hasExisting ? 'Add selected' : 'Accept selected'}
            </button>
          </div>
        </div>
      )}
    </section>
  )
}
