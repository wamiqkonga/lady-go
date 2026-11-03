import type { ReactNode } from 'react'
import { useSession } from './sessionContext'

/** Cosmetic: hides a screen from a role that cannot use it. The server returns 403 whatever this shows. */
export default function RequireRole({ role, children }: { role: string; children: ReactNode }) {
  const session = useSession()
  if (session.role !== role) {
    return <p role="alert">This page is only for the {role.replace('_', ' ').toLowerCase()} role.</p>
  }
  return <>{children}</>
}
