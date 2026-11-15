import { EyeOff, Icon } from '../icons'
import { initialsOf } from '../lib/format'
import { cx } from './cx'

/**
 * Avatar (§9.28): 24/32 px circle, --bg-active, caption initials in --fg-muted. Blind applicants never get
 * initials: they get the EyeOff icon in the same circle. Decorative by default (the name is shown next to it).
 */
export function Avatar({ name, size = 32, blind, label, className }: { name?: string; size?: 24 | 28 | 32; blind?: boolean; label?: string; className?: string }) {
  return (
    <span
      className={cx('ui-avatar', className)}
      data-size={size}
      data-blind={blind || undefined}
      role={label ? 'img' : undefined}
      aria-label={label}
      aria-hidden={label ? undefined : true}
    >
      {blind || !name ? <Icon icon={EyeOff} size={size === 24 ? 12 : 14} /> : initialsOf(name)}
    </span>
  )
}
