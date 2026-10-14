import { useEffect, useState, type ReactNode } from 'react'
import { useNavigate } from 'react-router-dom'
import { logout, onSessionChange } from './auth'
import { SessionContext } from './sessionContext'

/** No silent session restore on mount - there is no cookie to restore from (see auth.ts's own
 *  comment on why tokens are memory-only here). Starts signed out; signing in is always an explicit
 *  OTP flow, never implicit. */
export function SessionProvider({ children }: { children: ReactNode }) {
  const [signedIn, setSignedIn] = useState(false)
  const navigate = useNavigate()

  useEffect(() => onSessionChange((token) => setSignedIn(token !== null)), [])

  const signOut = async () => {
    await logout()
    navigate('/login', { replace: true })
  }

  return <SessionContext.Provider value={{ signedIn, signOut }}>{children}</SessionContext.Provider>
}
