import { Navigate, Route, Routes } from 'react-router-dom'
import Layout from './components/Layout'
import Dashboard from './pages/Dashboard'
import Login from './pages/Login'
import Placeholder from './pages/Placeholder'

export default function App() {
  return (
    <Routes>
      <Route path="login" element={<Login />} />
      <Route element={<Layout />}>
        <Route index element={<Navigate to="/dashboard" replace />} />
        <Route path="dashboard" element={<Dashboard />} />
        <Route
          path="live-map"
          element={<Placeholder title="Live map" phase="Phase 7 (real-time) and Phase 9" description="Online drivers, active rides, pickup/destination and ride status in real time." />}
        />
        <Route
          path="rides"
          element={<Placeholder title="Rides" phase="Phase 9" description="Search, filter and inspect rides, including the event timeline." />}
        />
        <Route
          path="drivers"
          element={<Placeholder title="Drivers" phase="Phase 9" description="Driver list, documents, ride history, earnings, suspend/activate." />}
        />
        <Route
          path="driver-verification"
          element={<Placeholder title="Driver verification" phase="Phase 5 and Phase 9" description="Review documents and approve or reject driver applications." />}
        />
        <Route
          path="customers"
          element={<Placeholder title="Customers" phase="Phase 9" description="Customer search, ride and payment history, suspend/activate." />}
        />
        <Route
          path="pricing"
          element={<Placeholder title="Pricing and configuration" phase="Phase 9" description="Fares, fees, search radius and timeouts. Changes are versioned and audited." />}
        />
        <Route
          path="safety"
          element={<Placeholder title="Safety incidents" phase="Phase 10" description="SOS and report queue with acknowledgement and resolution timeline." />}
        />
        <Route
          path="audit-logs"
          element={<Placeholder title="Audit log" phase="Phase 9" description="Append-only record of administrative actions." />}
        />
        <Route path="*" element={<Placeholder title="Page not found" phase="" description="This admin page does not exist." />} />
      </Route>
    </Routes>
  )
}
