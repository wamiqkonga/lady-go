import { useEffect, useRef, useState } from 'react'
import { Link, NavLink, Outlet, useLocation } from 'react-router-dom'
import Logo from './Logo'
import ThemeToggle from './ThemeToggle'
import { useSession } from '../lib/sessionContext'
import { useSafetyBadge } from '../lib/useSafetyBadge'
import { ageLabel } from '../lib/safety'

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
  const location = useLocation()
  const navRef = useRef<HTMLElement>(null)
  const [edges, setEdges] = useState({ left: false, right: false })

  function updateEdges() {
    const el = navRef.current
    if (!el) return
    const left = el.scrollLeft > 4
    const right = el.scrollLeft + el.clientWidth < el.scrollWidth - 4
    setEdges((prev) => (prev.left === left && prev.right === right ? prev : { left, right }))
  }

  // On a phone the menu is one swipeable row: keep the page you are on in view, and show which sides have more.
  useEffect(() => {
    const active = navRef.current?.querySelector<HTMLElement>('a.active')
    active?.scrollIntoView?.({ inline: 'center', block: 'nearest' })
    updateEdges()
  }, [location.pathname])

  useEffect(() => {
    updateEdges()
    window.addEventListener('resize', updateEdges)
    return () => window.removeEventListener('resize', updateEdges)
  }, [])

  return (
    <div className="admin-shell">
      <aside className="sidebar">
        <div className="brand">
          <Logo size={30} />
          <span>Lady Go Admin</span>
        </div>
        <div className="nav-wrap">
          <nav aria-label="Admin" ref={navRef} onScroll={updateEdges}>
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
          <span className="nav-fade nav-fade-left" hidden={!edges.left} aria-hidden="true" data-testid="nav-fade-left" />
          <span className="nav-fade nav-fade-right" hidden={!edges.right} aria-hidden="true" data-testid="nav-fade-right" />
        </div>
        <ThemeToggle />
        <button type="button" className="sign-out" onClick={signOut}>
          Sign out
        </button>
      </aside>
      <div className="main">
        {safety && safety.critical > 0 && (
          <div className="sos-banner" role="alert">
            <span>
              SOS open: {safety.critical}
              {safety.oldestCriticalAt ? `, raised ${ageLabel(safety.oldestCriticalAt)}` : ''}.
            </span>
            <Link to="/safety">Open the queue</Link>
          </div>
        )}
        <main className="content">
          <Outlet />
        </main>
      </div>
    </div>
  )
}
