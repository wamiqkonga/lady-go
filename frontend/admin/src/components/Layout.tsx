import { NavLink, Outlet } from 'react-router-dom'
import Logo from './Logo'
import ThemeToggle from './ThemeToggle'
import { useSession } from '../lib/sessionContext'

const nav = [
  { to: '/dashboard', label: 'Dashboard' },
  { to: '/live-map', label: 'Live map' },
  { to: '/rides', label: 'Rides' },
  { to: '/drivers', label: 'Drivers' },
  { to: '/driver-verification', label: 'Verification' },
  { to: '/customers', label: 'Customers' },
  { to: '/safety', label: 'Safety' },
  { to: '/pricing', label: 'Pricing' },
  { to: '/audit-logs', label: 'Audit log' },
]

export default function Layout() {
  const { signOut } = useSession()
  return (
    <div className="admin-shell">
      <aside className="sidebar">
        <div className="brand">
          <Logo size={30} />
          <span>Lady Go Admin</span>
        </div>
        <nav aria-label="Admin">
          {nav.map((n) => (
            <NavLink key={n.to} to={n.to}>
              {n.label}
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
