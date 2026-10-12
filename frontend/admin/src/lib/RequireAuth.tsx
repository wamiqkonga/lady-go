import type { ReactNode } from 'react'
import { Navigate } from 'react-router-dom'
import { useSession } from './sessionContext'

export default function RequireAuth({ children }: { children: ReactNode }) {
  const { signedIn } = useSession()
  if (signedIn === null) {
    return <p className="muted">Checking session…</p>
  }
  if (!signedIn) {
    return <Navigate to="/login" replace />
  }
  return children
}
