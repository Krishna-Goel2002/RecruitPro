import { useRef } from 'react'
import { Monitor, Moon, Sun } from '../icons'
import { useTheme, type ThemePreference } from '../theme'
import { IconButton } from './IconButton'
import { SegmentedControl } from './SegmentedControl'

/**
 * Theme switch (§10). `segmented` (account menu): System / Light / Dark with icons and labels.
 * `icon` (auth and referee pages): one button that flips between light and dark from where it is (M14).
 */
export function ThemeSwitch({ variant = 'segmented', className }: { variant?: 'segmented' | 'icon'; className?: string }) {
  const { preference, resolved, setPreference } = useTheme()
  const ref = useRef<HTMLDivElement>(null)
  if (variant === 'icon') {
    const next = resolved === 'dark' ? 'light' : 'dark'
    return (
      <IconButton
        className={className}
        label={next === 'dark' ? 'Switch to dark theme' : 'Switch to light theme'}
        icon={resolved === 'dark' ? Sun : Moon}
        onClick={e => setPreference(next, e.currentTarget)}
      />
    )
  }
  return (
    <div ref={ref} className={className}>
      <SegmentedControl<ThemePreference>
        label="Theme"
        size="sm"
        block
        value={preference}
        onChange={p => setPreference(p, ref.current?.querySelector<HTMLElement>(`input[value="${p}"]`)?.parentElement)}
        options={[
          { value: 'system', label: 'System', icon: Monitor },
          { value: 'light', label: 'Light', icon: Sun },
          { value: 'dark', label: 'Dark', icon: Moon },
        ]}
      />
    </div>
  )
}
