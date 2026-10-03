import { NavLink, Outlet } from 'react-router-dom'

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
  return (
    <div className="admin-shell">
      <aside className="sidebar">
        <div className="brand">Lady Go Admin</div>
        <nav aria-label="Admin">
          {nav.map((n) => (
            <NavLink key={n.to} to={n.to}>
              {n.label}
            </NavLink>
          ))}
        </nav>
      </aside>
      <div className="main">
        <div className="banner" role="status">
          Not signed in. Authentication and role checks are enforced by the backend and arrive in
          Phase 3; this screen shows no real data.
        </div>
        <main className="content">
          <Outlet />
        </main>
      </div>
    </div>
  )
}
