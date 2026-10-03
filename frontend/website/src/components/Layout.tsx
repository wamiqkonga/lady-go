import { useState } from 'react'
import { Link, NavLink, Outlet } from 'react-router-dom'

const links = [
  { to: '/about', label: 'About' },
  { to: '/how-it-works', label: 'How it works' },
  { to: '/safety', label: 'Safety' },
  { to: '/driver', label: 'Drive with us' },
  { to: '/faq', label: 'FAQ' },
  { to: '/contact', label: 'Contact' },
]

const YEAR = new Date().getFullYear()

export default function Layout() {
  const [open, setOpen] = useState(false)
  return (
    <div className="shell">
      <header className="site-header">
        <Link to="/" className="brand" onClick={() => setOpen(false)}>
          Lady Go
        </Link>
        <button
          type="button"
          className="menu-toggle"
          aria-expanded={open}
          aria-controls="main-nav"
          onClick={() => setOpen((v) => !v)}
        >
          Menu
        </button>
        <nav id="main-nav" aria-label="Main" className={open ? 'nav open' : 'nav'}>
          {links.map((l) => (
            <NavLink key={l.to} to={l.to} onClick={() => setOpen(false)}>
              {l.label}
            </NavLink>
          ))}
          <NavLink to="/login" className="nav-cta" onClick={() => setOpen(false)}>
            Log in
          </NavLink>
        </nav>
      </header>
      <main className="content">
        <Outlet />
      </main>
      <footer className="site-footer">
        <p>© {YEAR} Lady Go</p>
        <p>
          <Link to="/terms">Terms</Link> · <Link to="/privacy">Privacy</Link>
        </p>
      </footer>
    </div>
  )
}
