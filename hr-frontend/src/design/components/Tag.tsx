import type { HTMLAttributes, ReactNode } from 'react'
import { Icon, LockKeyhole, X, type IconComponent } from '../icons'
import { cx } from './cx'

export interface TagProps extends HTMLAttributes<HTMLSpanElement> {
  icon?: IconComponent
  /** Makes it a removable chip (chips input): adds an × button with a 24 px hit area. */
  onRemove?: () => void
  removeLabel?: string
}

/** Attribute tag (§9.9): plain caption text in --fg-muted with an optional 12 px icon; a background only when removable. */
export function Tag({ icon, onRemove, removeLabel, className, children, ...rest }: TagProps) {
  return (
    <span className={cx('ui-tag', className)} data-removable={onRemove ? true : undefined} {...rest}>
      {icon && <Icon icon={icon} size={12} />}
      <span>{children}</span>
      {onRemove && (
        <button type="button" className="ui-tag-remove" aria-label={removeLabel ?? `Remove ${typeof children === 'string' ? children : ''}`.trim()} onClick={onRemove}>
          <Icon icon={X} size={12} />
        </button>
      )}
    </span>
  )
}

/** Requirement markers (§9.9): must-have = solid square, nice-to-have = hollow square, ranking gate = lock. Never red. */
export function ReqMarker({ kind, children }: { kind: 'must' | 'nice' | 'gate'; children?: ReactNode }) {
  const text = children ?? (kind === 'must' ? 'Must-have' : kind === 'nice' ? 'Nice to have' : 'Gate')
  return (
    <span className="ui-req-marker" data-kind={kind}>
      {kind === 'gate' ? <Icon icon={LockKeyhole} size={12} /> : <span className="ui-req-square" aria-hidden="true" />}
      <span>{text}</span>
    </span>
  )
}
