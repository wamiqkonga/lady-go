import type { ReactNode } from 'react'
import { Navigate, useLocation } from 'react-router-dom'
import { useSession } from './sessionContext'

export default function RequireAuth({ children }: { children: ReactNode }) {
  const { signedIn } = useSession()
  const location = useLocation()
  if (signedIn === null) {
    return <p className="muted">Checking session…</p>
  }
  if (!signedIn) {
    return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />
  }
  return children
}
