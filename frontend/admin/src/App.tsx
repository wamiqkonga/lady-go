import { Navigate, Route, Routes } from 'react-router-dom'
import Layout from './components/Layout'
import AdminUserDetail from './pages/AdminUserDetail'
import AdminUsers from './pages/AdminUsers'
import AuditLog from './pages/AuditLog'
import CustomerDetail from './pages/CustomerDetail'
import Customers from './pages/Customers'
import Pricing from './pages/pricing/Pricing'
import Dashboard from './pages/Dashboard'
import DriverDocumentQueue from './pages/DriverDocumentQueue'
import DriverDetail from './pages/DriverDetail'
import DriverReview from './pages/DriverReview'
import DriverVerificationQueue from './pages/DriverVerificationQueue'
import Drivers from './pages/Drivers'
import ForgotPassword from './pages/ForgotPassword'
import LiveMap from './pages/LiveMap'
import Login from './pages/Login'
import ResetPassword from './pages/ResetPassword'
import Placeholder from './pages/Placeholder'
import RideDetail from './pages/RideDetail'
import RideOversight from './pages/RideOversight'
import SafetyIncident from './pages/SafetyIncident'
import SafetyQueue from './pages/SafetyQueue'
import RequireAuth from './lib/RequireAuth'
import RequireRole from './lib/RequireRole'
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
        <Route path="live-map" element={<LiveMap />} />
        <Route path="rides" element={<RideOversight />} />
        <Route path="rides/:bookingId" element={<RideDetail />} />
        <Route path="drivers" element={<Drivers />} />
        <Route path="drivers/:driverId" element={<DriverDetail />} />
        <Route path="driver-verification" element={<DriverVerificationQueue />} />
        <Route path="driver-verification/documents" element={<DriverDocumentQueue />} />
        <Route path="driver-verification/:driverId" element={<DriverReview />} />
        <Route path="customers" element={<Customers />} />
        <Route path="customers/:customerId" element={<CustomerDetail />} />
        <Route path="pricing/*" element={<Pricing />} />
        <Route path="safety" element={<SafetyQueue />} />
        <Route path="safety/:incidentId" element={<SafetyIncident />} />
        <Route path="audit-logs" element={<AuditLog />} />
        <Route path="users" element={<RequireRole role="SUPER_ADMIN"><AdminUsers /></RequireRole>} />
        <Route path="users/:id" element={<RequireRole role="SUPER_ADMIN"><AdminUserDetail /></RequireRole>} />
        <Route path="security" element={<Security />} />
        <Route path="*" element={<Placeholder title="Page not found" phase="" description="This admin page does not exist." />} />
        </Route>
      </Routes>
      </StepUpProvider>
    </SessionProvider>
  )
}
