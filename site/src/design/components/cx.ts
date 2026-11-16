/** Joins class names, skipping falsy values. */
export function cx(...parts: (string | number | false | null | undefined)[]): string {
  return parts.filter(Boolean).join(' ')
}
