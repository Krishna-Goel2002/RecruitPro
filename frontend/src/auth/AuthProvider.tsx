import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { ApiError, blockRefreshUntilSignIn, refreshSession, setAccessToken, subscribeSession } from '../api/client'
import { authApi } from '../api/endpoints'
import type { AuthResponse, AuthUser, LoginRequest, RegisterRequest } from '../api/types'

export type AuthStatus = 'loading' | 'authenticated' | 'anonymous'

/** Why the visitor is signed out; shown as a notice on the sign-in page. */
export type SignedOutReason = 'signed-out' | 'expired' | 'staff-session' | 'unreachable' | 'account-deleted' | 'password-reset'

interface AuthState {
  status: AuthStatus
  user: AuthUser | null
  reason: SignedOutReason | null
}

interface AuthContextValue extends AuthState {
  login: (body: LoginRequest) => Promise<AuthUser>
  register: (body: RegisterRequest) => Promise<AuthUser>
  logout: () => Promise<void>
  /** Use a session returned by the API (e.g. after a password change). */
  acceptSession: (auth: AuthResponse) => void
  /** Replace the user (e.g. after the email address was verified). */
  updateUser: (user: AuthUser) => void
  /** Drop the local session without calling the API (account deleted, password reset). */
  endSession: (reason: SignedOutReason) => void
  /** Try to restore the session again (after "server unreachable"). */
  restore: () => void
}

const AuthContext = createContext<AuthContextValue | null>(null)

const STAFF_MESSAGE = 'This portal is for candidate accounts. Staff members sign in on the HR portal.'

export function AuthProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient()
  const [state, setState] = useState<AuthState>({ status: 'loading', user: null, reason: null })
  const restoring = useRef(false)

  const signOutLocally = useCallback(
    (reason: SignedOutReason | null) => {
      // After account deletion the server already revoked the sessions and cleared the cookie: never refresh again
      // in this page load (a reload starts clean and simply finds no session).
      if (reason === 'account-deleted') blockRefreshUntilSignIn()
      else setAccessToken(null)
      setState({ status: 'anonymous', user: null, reason })
      void queryClient.cancelQueries()
      queryClient.clear()
    },
    [queryClient],
  )

  const restore = useCallback(() => {
    if (restoring.current) return
    restoring.current = true
    setState(current => ({ ...current, status: 'loading' }))
    refreshSession()
      .then(auth => {
        if (!auth) {
          setState({ status: 'anonymous', user: null, reason: null })
        } else if (auth.user.role !== 'CANDIDATE') {
          // Defence in depth: each portal has its own refresh cookie (X-RecruitPro-Portal) and the server refuses a
          // staff token on this portal with 403, so this should not happen. Never revoke a staff session from here.
          setAccessToken(null)
          setState({ status: 'anonymous', user: null, reason: 'staff-session' })
        } else {
          setState({ status: 'authenticated', user: auth.user, reason: null })
        }
      })
      .catch(() => {
        setAccessToken(null)
        setState({ status: 'anonymous', user: null, reason: 'unreachable' })
      })
      .finally(() => {
        restoring.current = false
      })
  }, [])

  // Restore the session from the httpOnly refresh cookie once on load (StrictMode-safe: refresh is single-flight).
  useEffect(() => {
    restore()
  }, [restore])

  useEffect(
    () =>
      subscribeSession({
        onRefreshed: auth => {
          if (auth.user.role !== 'CANDIDATE') return
          setState(current => (current.status === 'authenticated' ? { ...current, user: auth.user } : current))
        },
        onExpired: () => {
          setState(current => (current.status === 'authenticated' ? { status: 'anonymous', user: null, reason: 'expired' } : current))
          queryClient.clear()
        },
      }),
    [queryClient],
  )

  const acceptSession = useCallback((auth: AuthResponse) => {
    setAccessToken(auth.accessToken)
    setState({ status: 'authenticated', user: auth.user, reason: null })
  }, [])

  const startSession = useCallback(
    async (auth: AuthResponse): Promise<AuthUser> => {
      if (auth.user.role !== 'CANDIDATE') {
        // Defence in depth (the server already answers 403 for staff accounts on this portal): we created this
        // session, so revoke it again and clear the cookie (logout uses the cookie, not the token).
        setAccessToken(null)
        await authApi.logout().catch(() => undefined)
        throw new ApiError(STAFF_MESSAGE, 403)
      }
      queryClient.clear()
      acceptSession(auth)
      return auth.user
    },
    [acceptSession, queryClient],
  )

  const login = useCallback(async (body: LoginRequest) => startSession(await authApi.login(body)), [startSession])
  const register = useCallback(async (body: RegisterRequest) => startSession(await authApi.register(body)), [startSession])

  const logout = useCallback(async () => {
    try {
      await authApi.logout()
    } catch {
      // The cookie may already be gone; the local session is cleared either way.
    }
    signOutLocally('signed-out')
  }, [signOutLocally])

  const updateUser = useCallback((user: AuthUser) => {
    setState(current => (current.status === 'authenticated' && current.user?.id === user.id ? { ...current, user } : current))
  }, [])

  const value = useMemo<AuthContextValue>(
    () => ({ ...state, login, register, logout, acceptSession, updateUser, endSession: signOutLocally, restore }),
    [state, login, register, logout, acceptSession, updateUser, signOutLocally, restore],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext)
  if (!context) throw new Error('useAuth must be used inside <AuthProvider>')
  return context
}

/** The signed-in user; only call inside routes guarded by <RequireAuth>. */
export function useCurrentUser(): AuthUser {
  const { user } = useAuth()
  if (!user) throw new Error('useCurrentUser called without a signed-in user')
  return user
}
