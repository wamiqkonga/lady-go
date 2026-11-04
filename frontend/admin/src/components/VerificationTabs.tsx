import { NavLink } from 'react-router-dom'

/** The two ways into driver verification: the captain queue (main) and the document-by-document queue. */
export default function VerificationTabs() {
  return (
    <nav className="tabs" aria-label="Verification views">
      <NavLink to="/driver-verification" end className={({ isActive }) => `tab${isActive ? ' active' : ''}`}>
        Captains
      </NavLink>
      <NavLink to="/driver-verification/documents" className={({ isActive }) => `tab${isActive ? ' active' : ''}`}>
        Documents
      </NavLink>
    </nav>
  )
}
