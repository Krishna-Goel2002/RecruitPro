import { Check, Circle, Icon } from '../design/icons'
import './hra.css'

/**
 * Password rules exactly as the server checks them (backend PasswordPolicy: 10–72 characters, at least one Unicode
 * letter and one Unicode digit; BCrypt reads at most 72 bytes, so the limit is counted in UTF-8 bytes).
 */
export function passwordRules(password: string) {
  const bytes = new TextEncoder().encode(password).length
  // The server checks whole code points (R6-B7: Character.isLetter/isDigit over codePoints(), the same as its DTO
  // regex), so a letter outside the Basic Multilingual Plane counts, as it does in /\p{L}/u. The length rule counts
  // UTF-16 units on both sides (Java String.length(), JS string length).
  return [
    { id: 'length', label: 'At least 10 characters', met: password.length >= 10 },
    { id: 'letter', label: 'A letter', met: /\p{L}/u.test(password) },
    { id: 'digit', label: 'A digit', met: /\p{Nd}/u.test(password) },
    ...(bytes > 72 ? [{ id: 'max', label: 'At most 72 bytes (shorter, or fewer special characters)', met: false }] : []),
  ]
}

/** First unmet rule as a sentence for the field error, or null when the password is acceptable. */
export function passwordError(password: string): string | null {
  if (!password) return 'Choose a password.'
  const missing = passwordRules(password).filter(rule => !rule.met)
  if (!missing.length) return null
  if (missing.some(rule => rule.id === 'max')) return 'This password is too long. Use at most 72 bytes.'
  return `Add ${missing.map(rule => rule.label.toLowerCase()).join(', ')}.`
}

/** Live checklist under a new-password field: each rule ticks (SVG, never a glyph) as it is met. */
export function PasswordChecklist({ password, id }: { password: string; id?: string }) {
  const rules = passwordRules(password)
  return (
    <ul className="hra-pw-rules" id={id} aria-label="Password requirements">
      {rules.map(rule => (
        <li key={rule.id} data-met={rule.met || undefined}>
          <Icon icon={rule.met ? Check : Circle} size={12} />
          <span>{rule.label}</span>
          <span className="sr-only">{rule.met ? ' (met)' : ' (not yet)'}</span>
        </li>
      ))}
    </ul>
  )
}
