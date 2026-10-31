import { useEffect, useState } from 'react'
import { Link, NavLink, Outlet, useLocation } from 'react-router-dom'
import Icon from './Icons'
import Logo from './Logo'

const links = [
  { to: '/about', label: 'About' },
  { to: '/how-it-works', label: 'How it works' },
  { to: '/safety', label: 'Safety' },
  { to: '/captain', label: 'Drive with us' },
  { to: '/faq', label: 'FAQ' },
  { to: '/contact', label: 'Contact' },
]

const YEAR = new Date().getFullYear()

export default function Layout() {
  const [open, setOpen] = useState(false)
  const { pathname } = useLocation()

  // A new page always starts at the top.
  useEffect(() => {
    if (!navigator.userAgent.includes('jsdom')) window.scrollTo(0, 0)
  }, [pathname])

  useEffect(() => {
    if (!open) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false)
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [open])

  return (
    <div className="shell">
      <a className="skip-link" href="#main">
        Skip to main content
      </a>
      <header className="site-header">
        <div className="container header-row">
          <Link to="/" className="brand" aria-label="Lady Go home" onClick={() => setOpen(false)}>
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
            <Icon name={open ? 'close' : 'menu'} size={20} />
            <span>Menu</span>
          </button>
          <nav id="main-nav" aria-label="Main" className={open ? 'nav open' : 'nav'}>
            <ul className="nav-links">
              {links.map((l) => (
                <li key={l.to}>
                  <NavLink to={l.to} onClick={() => setOpen(false)}>
                    {l.label}
                  </NavLink>
                </li>
              ))}
            </ul>
            <div className="nav-actions">
              <Link to="/captain/registration" className="button button-sm" onClick={() => setOpen(false)}>
                Captain registration
              </Link>
            </div>
          </nav>
        </div>
      </header>
      <main id="main" className="main" tabIndex={-1}>
        <Outlet />
      </main>
      <footer className="site-footer">
        <div className="container footer-grid">
          <div className="footer-about">
            <Link to="/" className="brand" aria-label="Lady Go home">
              <Logo size={30} />
              <span>Lady Go</span>
            </Link>
            <p>Safe, simple pick-and-drop rides, built for women and driven by women.</p>
          </div>
          <nav aria-label="Rider">
            <h2 className="footer-title">Riders</h2>
            <ul>
              <li><Link to="/how-it-works">How it works</Link></li>
              <li><Link to="/safety">Safety</Link></li>
              <li><Link to="/faq">FAQ</Link></li>
            </ul>
          </nav>
          <nav aria-label="Captains">
            <h2 className="footer-title">Captains</h2>
            <ul>
              <li><Link to="/captain">Drive with Lady Go</Link></li>
              <li><Link to="/captain/registration">Register or check status</Link></li>
            </ul>
          </nav>
          <nav aria-label="Company">
            <h2 className="footer-title">Company</h2>
            <ul>
              <li><Link to="/about">About</Link></li>
              <li><Link to="/contact">Contact</Link></li>
              <li><Link to="/terms">Terms</Link></li>
              <li><Link to="/privacy">Privacy</Link></li>
            </ul>
          </nav>
        </div>
        <div className="container footer-base">
          <p>© {YEAR} Lady Go. All rights reserved.</p>
          <p>In an emergency, call 112 first.</p>
        </div>
      </footer>
    </div>
  )
}
