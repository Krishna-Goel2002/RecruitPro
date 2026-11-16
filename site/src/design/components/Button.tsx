import { forwardRef, useLayoutEffect, useRef, type ButtonHTMLAttributes } from 'react'
import { Icon, type IconComponent } from '../icons'
import { cx } from './cx'
import { mergeRefs } from './hooks'
import { Spinner } from './Spinner'

export type ButtonVariant = 'primary' | 'secondary' | 'ghost' | 'danger' | 'danger-ghost' | 'link'
export type ButtonSize = 'sm' | 'md' | 'lg'

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant
  size?: ButtonSize
  /** Shows a spinner in place of the leading icon, keeps the label, locks the width and sets aria-busy. */
  loading?: boolean
  icon?: IconComponent
  trailingIcon?: IconComponent
  /** Full width (auth forms, phone primary). */
  block?: boolean
}

/**
 * Button (§9.1). Variants via data-variant, sizes via data-size; state from native/ARIA attributes.
 * Never disable a Save button to mean "not saved yet": keep it enabled and validate on press.
 */
export const Button = forwardRef<HTMLButtonElement, ButtonProps>(function Button(
  { variant = 'secondary', size = 'md', loading = false, icon, trailingIcon, block, className, children, type = 'button', onClick, ...rest },
  ref,
) {
  const local = useRef<HTMLButtonElement>(null)
  // Lock the width while busy so the layout does not jump when the spinner appears.
  useLayoutEffect(() => {
    const el = local.current
    if (!el) return
    if (loading) el.style.minWidth = `${el.getBoundingClientRect().width}px`
    else el.style.minWidth = ''
  }, [loading])
  const iconSize = size === 'lg' ? 16 : size === 'sm' ? 14 : 16
  return (
    <button
      ref={mergeRefs(ref, local)}
      type={type}
      className={cx('ui-btn', className)}
      data-variant={variant}
      data-size={size}
      data-block={block || undefined}
      aria-busy={loading || undefined}
      onClick={loading ? e => e.preventDefault() : onClick}
      {...rest}
    >
      {loading ? <Spinner size={iconSize} /> : icon ? <Icon icon={icon} size={iconSize} /> : null}
      {children != null && <span className="ui-btn-label">{children}</span>}
      {trailingIcon && !loading && <Icon icon={trailingIcon} size={iconSize} className="ui-btn-trailing" />}
    </button>
  )
})

/** Button styling for a link (<Link {...buttonProps('primary')}>). */
export function buttonProps(variant: ButtonVariant = 'secondary', size: ButtonSize = 'md', className?: string) {
  return { className: cx('ui-btn', className), 'data-variant': variant, 'data-size': size }
}
