import { createContext, useContext } from 'react'
import { getAccessToken } from './auth'

export type SessionState = {
  /** null = not yet known (still bootstrapping); undefined would be ambiguous with "logged out". */
  signedIn: boolean | null
  /** Display/UI-hiding only - see getRole()'s own doc comment. Null while signedIn isn't true yet. */
  role: string | null
  signOut: () => Promise<void>
}

export const SessionContext = createContext<SessionState | null>(null)

export function useSession(): SessionState {
  const ctx = useContext(SessionContext)
  if (!ctx) throw new Error('useSession must be used within SessionProvider')
  return ctx
}

/** For call sites that just need a yes/no right now, outside the provider's own state (e.g. a
 *  one-off check before a fetch) - prefer useSession() inside components. */
export function isSignedIn(): boolean {
  return getAccessToken() !== null
}
