import { useState, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { ChevronDown, Icon, LogOut, type IconComponent } from '../icons'
import { Avatar } from './Avatar'
import { Popover } from './Menu'
import { Spinner } from './Spinner'
import { ThemeSwitch } from './ThemeSwitch'

export interface AccountLink {
  label: string
  to: string
  icon?: IconComponent
}

export interface AccountMenuProps {
  name: string
  email?: string
  /** Role or organisation line under the name. */
  detail?: ReactNode
  links?: AccountLink[]
  onSignOut: () => void
  signingOut?: boolean
}

/**
 * Account menu (§6, X-12): an avatar button in the top bar opening a panel with the name, email and role, the theme
 * switch (System / Light / Dark), account links and Sign out. A disclosure (aria-expanded), not an ARIA menu,
 * because it holds a radio group.
 */
export function AccountMenu({ name, email, detail, links = [], onSignOut, signingOut }: AccountMenuProps) {
  const [anchor, setAnchor] = useState<HTMLButtonElement | null>(null)
  const [open, setOpen] = useState(false)
  return (
    <>
      <button
        ref={setAnchor}
        type="button"
        className="p-account-btn"
        aria-expanded={open}
        aria-haspopup="dialog"
        onClick={() => setOpen(o => !o)}
      >
        <Avatar name={name} size={28} />
        <span className="p-account-btn-name" title={name}>{name}</span>
        <span className="sr-only">, account menu</span>
        <Icon icon={ChevronDown} size={14} className="p-account-chevron" />
      </button>
      <Popover anchor={anchor} open={open} onClose={() => setOpen(false)} label="Account" className="p-account-panel">
        <div className="p-account-head">
          <Avatar name={name} size={32} />
          <div className="p-account-id">
            <p className="p-account-name">{name}</p>
            {email && <p className="p-account-email truncate">{email}</p>}
            {detail && <p className="p-account-detail">{detail}</p>}
          </div>
        </div>
        <div className="p-account-section">
          <p className="p-account-section-label" id="account-theme-label">
            Theme
          </p>
          <ThemeSwitch />
        </div>
        {links.length > 0 && (
          <ul className="p-account-links" role="list">
            {links.map(l => (
              <li key={l.to}>
                <Link to={l.to} className="p-account-link" onClick={() => setOpen(false)}>
                  {l.icon && <Icon icon={l.icon} size={16} />}
                  {l.label}
                </Link>
              </li>
            ))}
          </ul>
        )}
        <div className="p-account-footer">
          <button type="button" className="p-account-link" onClick={onSignOut} disabled={signingOut} aria-busy={signingOut || undefined}>
            {signingOut ? <Spinner size={16} /> : <Icon icon={LogOut} size={16} />}
            {signingOut ? 'Signing out…' : 'Sign out'}
          </button>
        </div>
      </Popover>
    </>
  )
}
