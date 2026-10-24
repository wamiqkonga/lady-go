import { useEffect, useState, type ReactNode } from 'react'
import { useNavigate } from 'react-router-dom'
import { bootstrapSession, getRole, logout, onSessionChange } from './auth'
import { startIdleTimeout } from './idleTimeout'
import { SessionContext } from './sessionContext'

export function SessionProvider({ children }: { children: ReactNode }) {
  const [signedIn, setSignedIn] = useState<boolean | null>(null)
  const [role, setRole] = useState<string | null>(null)
  const navigate = useNavigate()

  useEffect(() => {
    const unsubscribe = onSessionChange((token) => {
      setSignedIn(token !== null)
      setRole(getRole())
    })
    bootstrapSession().then((ok) => {
      setSignedIn(ok)
      setRole(getRole())
    })
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

  return <SessionContext.Provider value={{ signedIn, role, signOut }}>{children}</SessionContext.Provider>
}
