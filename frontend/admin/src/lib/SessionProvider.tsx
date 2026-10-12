import { useEffect, useState, type ReactNode } from 'react'
import { useNavigate } from 'react-router-dom'
import { bootstrapSession, logout, onSessionChange } from './auth'
import { startIdleTimeout } from './idleTimeout'
import { SessionContext } from './sessionContext'

export function SessionProvider({ children }: { children: ReactNode }) {
  const [signedIn, setSignedIn] = useState<boolean | null>(null)
  const navigate = useNavigate()

  useEffect(() => {
    const unsubscribe = onSessionChange((token) => setSignedIn(token !== null))
    bootstrapSession().then((ok) => setSignedIn(ok))
    return unsubscribe
  }, [])

  const signOut = async () => {
    await logout()
    navigate('/login', { replace: true })
  }

  useEffect(() => {
    if (!signedIn) return
    return startIdleTimeout(() => {
      signOut()
    })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [signedIn])

  return <SessionContext.Provider value={{ signedIn, signOut }}>{children}</SessionContext.Provider>
}
