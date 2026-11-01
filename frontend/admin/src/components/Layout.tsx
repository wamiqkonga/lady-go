import { NavLink, Outlet } from 'react-router-dom'
import Logo from './Logo'
import ThemeToggle from './ThemeToggle'
import { useSession } from '../lib/sessionContext'
import { useSafetyBadge } from '../lib/useSafetyBadge'

const nav: { to: string; label: string; superAdminOnly?: boolean }[] = [
  { to: '/dashboard', label: 'Dashboard' },
  { to: '/live-map', label: 'Live map' },
  { to: '/rides', label: 'Rides' },
  { to: '/drivers', label: 'Drivers' },
  { to: '/driver-verification', label: 'Verification' },
  { to: '/customers', label: 'Customers' },
  { to: '/safety', label: 'Safety' },
  { to: '/pricing', label: 'Pricing' },
  { to: '/audit-logs', label: 'Audit log' },
  { to: '/users', label: 'Staff', superAdminOnly: true },
  { to: '/security', label: 'Security' },
]

export default function Layout() {
  const { signOut, role } = useSession()
  const safety = useSafetyBadge()
  return (
    <div className="admin-shell">
      <aside className="sidebar">
        <div className="brand">
          <Logo size={30} />
          <span>Lady Go Admin</span>
        </div>
        <nav aria-label="Admin">
          {nav.filter((n) => !n.superAdminOnly || role === 'SUPER_ADMIN').map((n) => (
            <NavLink key={n.to} to={n.to}>
              {n.label}
              {n.to === '/safety' && safety && safety.unresolved > 0 && (
                <span className={`nav-badge${safety.critical > 0 ? ' nav-badge-critical' : ''}`} aria-label={`${safety.unresolved} unresolved`}>
                  {safety.unresolved}
                </span>
              )}
            </NavLink>
          ))}
        </nav>
        <ThemeToggle />
        <button type="button" className="sign-out" onClick={signOut}>
          Sign out
        </button>
      </aside>
      <div className="main">
        <main className="content">
          <Outlet />
        </main>
      </div>
    </div>
  )
}
