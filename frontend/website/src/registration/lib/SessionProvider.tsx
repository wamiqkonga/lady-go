import { useEffect, useState, type ReactNode } from 'react'
import { useNavigate } from 'react-router-dom'
import { hasStoredSession, logout, onSessionChange, restoreSession } from './auth'
import { SessionContext } from './sessionContext'

/** On load it tries to restore the session from the tab's stored refresh token (see auth.ts), and shows
 *  nothing but a short message until that attempt finishes, so a reload or deep link does not bounce a
 *  signed-in captain to the sign-in page. With no stored token it falls straight through as signed out. */
export function SessionProvider({ children }: { children: ReactNode }) {
  const [signedIn, setSignedIn] = useState(false)
  // Nothing stored means nothing to restore, so render at once (public pages must not flash a loading message).
  const [ready, setReady] = useState(() => !hasStoredSession())
  const navigate = useNavigate()

  useEffect(() => onSessionChange((token) => setSignedIn(token !== null)), [])
  useEffect(() => {
    if (ready) return
    restoreSession().then((ok) => {
      setSignedIn(ok)
      setReady(true)
    })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const signOut = async () => {
    await logout()
    navigate('/captain/registration', { replace: true })
  }

  if (!ready) return <p className="muted">Checking your session…</p>
  return <SessionContext.Provider value={{ signedIn, signOut }}>{children}</SessionContext.Provider>
}
