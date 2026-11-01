import { Navigate, Route, Routes } from 'react-router-dom'
import Layout from './components/Layout'
import About from './pages/About'
import Contact from './pages/Contact'
import DriverPage from './pages/DriverPage'
import Faq from './pages/Faq'
import Home from './pages/Home'
import HowItWorks from './pages/HowItWorks'
import NotFound from './pages/NotFound'
import Privacy from './pages/Privacy'
import Safety from './pages/Safety'
import Terms from './pages/Terms'
import RegistrationApp from './registration/RegistrationApp'
import RequireAuth from './registration/lib/RequireAuth'
import RegistrationDashboard from './registration/pages/Dashboard'
import RegistrationLogin from './registration/pages/Login'

export default function App() {
  return (
    <Routes>
      <Route element={<Layout />}>
        <Route index element={<Home />} />
        <Route path="about" element={<About />} />
        <Route path="how-it-works" element={<HowItWorks />} />
        <Route path="safety" element={<Safety />} />
        <Route path="captain" element={<DriverPage />} />
        <Route path="contact" element={<Contact />} />
        <Route path="faq" element={<Faq />} />
        <Route path="terms" element={<Terms />} />
        <Route path="privacy" element={<Privacy />} />
        <Route path="driver" element={<Navigate to="/captain" replace />} />
        <Route path="driver/login" element={<Navigate to="/captain/registration" replace />} />
        <Route path="driver/register" element={<Navigate to="/captain/register" replace />} />
        <Route path="login" element={<Navigate to="/captain/registration" replace />} />
        <Route path="*" element={<NotFound />} />
      </Route>
      <Route element={<RegistrationApp />}>
        <Route path="captain/registration" element={<RegistrationLogin />} />
        <Route
          path="captain/register"
          element={
            <RequireAuth>
              <RegistrationDashboard />
            </RequireAuth>
          }
        />
      </Route>
    </Routes>
  )
}
