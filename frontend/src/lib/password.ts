/** Password policy (plan 5.2): 10-72 characters, at least one letter and one digit. */
export const PASSWORD_MIN = 10
export const PASSWORD_MAX = 72

export interface PasswordCheck {
  id: 'length' | 'letter' | 'digit'
  label: string
  met: boolean
}

const encoder = new TextEncoder()

export function checkPassword(password: string): PasswordCheck[] {
  return [
    {
      id: 'length',
      label: `${PASSWORD_MIN} to ${PASSWORD_MAX} characters`,
      // The backend also caps the UTF-8 size at 72 bytes (BCrypt input limit).
      met:
        password.length >= PASSWORD_MIN &&
        password.length <= PASSWORD_MAX &&
        encoder.encode(password).length <= PASSWORD_MAX,
    },
    { id: 'letter', label: 'At least one letter', met: /\p{L}/u.test(password) },
    { id: 'digit', label: 'At least one digit', met: /\p{Nd}/u.test(password) },
  ]
}

export function isPasswordValid(password: string): boolean {
  return checkPassword(password).every(check => check.met)
}

export const PASSWORD_POLICY_TEXT = `Use ${PASSWORD_MIN} to ${PASSWORD_MAX} characters with at least one letter and one digit.`
