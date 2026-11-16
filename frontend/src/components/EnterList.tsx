import { useRef, type HTMLAttributes, type ReactNode } from 'react'
import { useEnterOnce } from '../lib/hooks'

/** A <ul> whose rows rise in one after another on first render only (M2); later updates do not replay it. */
export function EnterList({ children, ...rest }: HTMLAttributes<HTMLUListElement> & { children?: ReactNode; 'data-closed'?: string }) {
  const ref = useRef<HTMLUListElement>(null)
  useEnterOnce(ref, true)
  return (
    <ul ref={ref} {...rest}>
      {children}
    </ul>
  )
}
