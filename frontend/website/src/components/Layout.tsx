import { useState } from 'react'
import { Link, NavLink, Outlet } from 'react-router-dom'
import Logo from './Logo'
import ThemeToggle from './ThemeToggle'

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
          <Logo size={34} />
          <span>Lady Go</span>
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
          <NavLink to="/driver/login" className="nav-cta" onClick={() => setOpen(false)}>
            Driver login
          </NavLink>
          <ThemeToggle />
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
