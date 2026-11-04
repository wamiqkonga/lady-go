import { Navigate, NavLink, Route, Routes } from 'react-router-dom'
import { useSession } from '../../lib/sessionContext'
import ConfigScreen from '../ConfigScreen'
import FaresTab from './FaresTab'
import './pricing.css'

/** Pricing: Fares (SUPER_ADMIN only) and Settings (the existing configuration screen, unchanged). */
export default function Pricing() {
  const { role } = useSession()
  const canSeeFares = role === 'SUPER_ADMIN'
  return (
    <section>
      <h1>Pricing</h1>
      <nav className="tabs" aria-label="Pricing sections">
        {canSeeFares && <NavLink to="/pricing" end>Fares</NavLink>}
        <NavLink to="/pricing/settings">Settings</NavLink>
      </nav>
      <Routes>
        <Route index element={canSeeFares ? <FaresTab /> : <Navigate to="/pricing/settings" replace />} />
        <Route path="settings" element={<ConfigScreen />} />
        <Route path="*" element={<Navigate to="/pricing" replace />} />
      </Routes>
    </section>
  )
}
