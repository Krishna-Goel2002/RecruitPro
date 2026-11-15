import { Check, Icon } from '../icons'
import { cx } from './cx'

/** Horizontal stepper (§9.23): 20 px numbered circles (done = check, current = accent ring) joined by 1 px lines. */
export function Stepper({ steps, current, label = 'Progress', className }: { steps: string[]; current: number; label?: string; className?: string }) {
  return (
    <ol className={cx('ui-stepper', className)} aria-label={label}>
      {steps.map((s, i) => {
        const state = i < current ? 'done' : i === current ? 'current' : 'upcoming'
        return (
          <li key={s} className="ui-step" data-state={state} aria-current={state === 'current' ? 'step' : undefined}>
            <span className="ui-step-dot numeric" aria-hidden="true">
              {state === 'done' ? <Icon icon={Check} size={12} strokeWidth={2} /> : i + 1}
            </span>
            <span className="ui-step-label">
              {s}
              {state === 'done' && <span className="sr-only"> (done)</span>}
            </span>
          </li>
        )
      })}
    </ol>
  )
}
