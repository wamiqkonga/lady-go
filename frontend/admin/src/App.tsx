import { Navigate, Route, Routes } from 'react-router-dom'
import Layout from './components/Layout'
import AuditLog from './pages/AuditLog'
import ConfigScreen from './pages/ConfigScreen'
import Dashboard from './pages/Dashboard'
import DriverDocumentQueue from './pages/DriverDocumentQueue'
import DriverReview from './pages/DriverReview'
import ForgotPassword from './pages/ForgotPassword'
import Login from './pages/Login'
import ResetPassword from './pages/ResetPassword'
import Placeholder from './pages/Placeholder'
import RideDetail from './pages/RideDetail'
import RideOversight from './pages/RideOversight'
import RequireAuth from './lib/RequireAuth'
import Security from './pages/Security'
import { StepUpProvider } from './lib/StepUpProvider'
import { SessionProvider } from './lib/SessionProvider'

export default function App() {
  return (
    <SessionProvider>
      <StepUpProvider>
      <Routes>
        <Route path="login" element={<Login />} />
        <Route path="forgot-password" element={<ForgotPassword />} />
        <Route path="reset-password" element={<ResetPassword />} />
        <Route
          element={
            <RequireAuth>
              <Layout />
            </RequireAuth>
          }
        >
        <Route index element={<Navigate to="/dashboard" replace />} />
        <Route path="dashboard" element={<Dashboard />} />
        <Route
          path="live-map"
          element={<Placeholder title="Live map" phase="Phase 7 (real-time) and Phase 9" description="Online drivers, active rides, pickup/destination and ride status in real time." />}
        />
        <Route path="rides" element={<RideOversight />} />
        <Route path="rides/:bookingId" element={<RideDetail />} />
        <Route
          path="drivers"
          element={<Placeholder title="Drivers" phase="Phase 9" description="Driver list, documents, ride history, earnings, suspend/activate." />}
        />
        <Route path="driver-verification" element={<DriverDocumentQueue />} />
        <Route path="driver-verification/:driverId" element={<DriverReview />} />
        <Route
          path="customers"
          element={<Placeholder title="Customers" phase="Phase 9" description="Customer search, ride and payment history, suspend/activate." />}
        />
        <Route path="pricing" element={<ConfigScreen />} />
        <Route
          path="safety"
          element={<Placeholder title="Safety incidents" phase="Phase 10" description="SOS and report queue with acknowledgement and resolution timeline." />}
        />
        <Route path="audit-logs" element={<AuditLog />} />
        <Route path="security" element={<Security />} />
        <Route path="*" element={<Placeholder title="Page not found" phase="" description="This admin page does not exist." />} />
        </Route>
      </Routes>
      </StepUpProvider>
    </SessionProvider>
  )
}
