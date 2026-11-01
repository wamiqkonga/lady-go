import { Outlet } from 'react-router-dom'
import { SessionProvider } from './lib/SessionProvider'
import './registration.css'

/** Layout route for the captain self-registration flow (/captain/registration, /captain/register). Mounted
 *  outside the marketing Layout on purpose: the flow is a mobile-first, single-column app shell with
 *  its own top bar and a bottom-fixed primary action. */
export default function RegistrationApp() {
  return (
    <SessionProvider>
      <Outlet />
    </SessionProvider>
  )
}
