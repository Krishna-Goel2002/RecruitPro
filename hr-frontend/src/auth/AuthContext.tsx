import { createContext, ReactNode, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { authApi } from '../api/auth'
import { ApiError, onSessionChange, refreshSession, restoreSession, setSession } from '../api/client'
import type { AuthResponse, AuthUser } from '../api/types'
import { flushDeferredCommits } from '../lib/deferredCommit'
import { Capability, can as roleCan, isStaff } from './permissions'

type Status = 'restoring' | 'authenticated' | 'anonymous'

export type AuthContextValue = {
  status: Status
  user: AuthUser | null
  /** Why the last session ended (shown on the sign-in page). */
  endedReason: string | null
  /** True after the user pressed "Sign out" (so the next sign-in does not return to the previous page). */
  signedOutByUser: boolean
  can: (capability: Capability) => boolean
  /** Accepts an AuthResponse from login, signup or invitation acceptance; rejects non-staff accounts. */
  acceptSession: (auth: AuthResponse) => Promise<AuthUser>
  signOut: (reason?: string) => Promise<void>
  /**
   * Adopts a session whose refresh cookie the server set during a top-level navigation (SSO callback → /sso/complete):
   * refreshes with the staff portal header. Resolves the user, or null when there is no usable staff session.
   */
  completeExternalSignIn: () => Promise<AuthUser | null>
  /** Updates the signed-in user's cached fields after a change made elsewhere (email confirmed, organisation renamed). */
  updateUser: (patch: Partial<Pick<AuthUser, 'emailVerified' | 'organizationName' | 'firstName' | 'lastName'>>) => void
}

/** Exported for tests (they provide a fixed session); the app uses AuthProvider. */
export const AuthContext = createContext<AuthContextValue | null>(null)

/** Shown on the sign-in page after the user pressed "Sign out". */
export const SIGNED_OUT_NOTICE = 'You have signed out.'

export const NON_STAFF_MESSAGE = 'This workspace is for organisation staff. Candidates sign in on the candidate portal.'

export function AuthProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient()
  const [status, setStatus] = useState<Status>('restoring')
  const [user, setUser] = useState<AuthUser | null>(null)
  const [endedReason, setEndedReason] = useState<string | null>(null)
  const [signedOutByUser, setSignedOutByUser] = useState(false)
  const signingOut = useRef(false)
  const hadUser = useRef(false)

  // The client tells us whenever the session changes (login, refresh, or a failed refresh).
  useEffect(() => onSessionChange(auth => {
    if (auth && isStaff(auth.user.role)) {
      hadUser.current = true
      setUser(auth.user)
      setStatus('authenticated')
      return
    }
    if (!auth) {
      if (hadUser.current && !signingOut.current) setEndedReason('Your session has ended. Sign in again to continue.')
      hadUser.current = false
      setUser(null)
      setStatus('anonymous')
      queryClient.clear()
    }
  }), [queryClient])

  // Session restore: the refresh cookie is httpOnly, so ask the server for a fresh access token.
  useEffect(() => {
    let cancelled = false
    void restoreSession().then(async outcome => {
      if (cancelled) return
      if (outcome.kind === 'ok' && !isStaff(outcome.auth.user.role)) {
        await authApi.logout().catch(() => undefined)
        setSession(null)
        setEndedReason(NON_STAFF_MESSAGE)
      }
      if (outcome.kind !== 'ok') setStatus('anonymous')
    })
    return () => { cancelled = true }
  }, [])

  const acceptSession = useCallback(async (auth: AuthResponse) => {
    if (!isStaff(auth.user.role)) {
      // Do not keep a candidate session in the HR portal: revoke the cookie the server just set.
      await authApi.logout().catch(() => undefined)
      signingOut.current = true
      setSession(null)
      signingOut.current = false
      throw new ApiError(403, NON_STAFF_MESSAGE)
    }
    setEndedReason(null)
    setSignedOutByUser(false)
    setSession(auth)
    return auth.user
  }, [])

  const signOut = useCallback(async (reason?: string) => {
    signingOut.current = true
    setSignedOutByUser(true)
    try {
      // Decisions still inside their Undo window were confirmed by the user: send them while the session is valid.
      await flushDeferredCommits().catch(() => undefined)
      await authApi.logout()
    } catch {
      // The local session is cleared even when the server cannot be reached.
    } finally {
      setSession(null)
      setEndedReason(reason ?? null)
      signingOut.current = false
    }
  }, [])

  const completeExternalSignIn = useCallback(async () => {
    const outcome = await refreshSession()
    if (outcome.kind === 'network') throw new ApiError(0, 'The server could not be reached. Try again in a moment.')
    if (outcome.kind !== 'ok') return null
    if (!isStaff(outcome.auth.user.role)) {
      await authApi.logout().catch(() => undefined)
      setSession(null)
      setEndedReason(NON_STAFF_MESSAGE)
      return null
    }
    setEndedReason(null)
    setSignedOutByUser(false)
    return outcome.auth.user
  }, [])

  const updateUser = useCallback((patch: Partial<Pick<AuthUser, 'emailVerified' | 'organizationName' | 'firstName' | 'lastName'>>) => {
    setUser(current => (current ? { ...current, ...patch } : current))
  }, [])

  const value = useMemo<AuthContextValue>(() => ({
    status,
    user,
    endedReason,
    signedOutByUser,
    can: (capability: Capability) => roleCan(user?.role, capability),
    acceptSession,
    signOut,
    completeExternalSignIn,
    updateUser,
  }), [status, user, endedReason, signedOutByUser, acceptSession, signOut, completeExternalSignIn, updateUser])

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth(): AuthContextValue {
  const value = useContext(AuthContext)
  if (!value) throw new Error('useAuth must be used inside AuthProvider')
  return value
}

/** The signed-in user; only call below a RequireAuth guard. */
export function useCurrentUser(): AuthUser {
  const { user } = useAuth()
  if (!user) throw new Error('useCurrentUser needs an authenticated session')
  return user
}
