import { useCallback, useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { client, unwrap, ApiCallError } from '../lib/client'
import type { components } from '../lib/api-types'
import { useSession } from '../lib/sessionContext'
import { useStepUp } from '../lib/stepUpContext'
import { StatusBadge } from './Customers'
import './customers.css'

type Detail = components['schemas']['AdminCustomerDetailResponse']
type Action = 'suspend' | 'activate'

function fare(minor?: number, currency?: string) {
  if (minor === undefined || minor === null) return '—'
  try {
    return new Intl.NumberFormat(undefined, { style: 'currency', currency: currency || 'INR' }).format(minor / 100)
  } catch {
    return String(minor / 100)
  }
}

function StatusAction({ customerId, status, onChanged }: { customerId: string; status?: string; onChanged: (d: Detail) => void }) {
  const action: Action | null = status === 'ACTIVE' ? 'suspend' : status === 'SUSPENDED' ? 'activate' : null
  const [open, setOpen] = useState(false)
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [needsSetup, setNeedsSetup] = useState(false)
  const { runWithStepUp } = useStepUp()

  if (!action) return null
  const label = action === 'suspend' ? 'Suspend customer' : 'Re-activate customer'

  async function confirm() {
    if (!action || !reason.trim()) return
    setBusy(true)
    setError(null)
    setNeedsSetup(false)
    try {
      const detail = await runWithStepUp(async () => {
        const path = action === 'suspend' ? '/api/v1/admin/customers/{id}/suspend' : '/api/v1/admin/customers/{id}/activate'
        return unwrap(await client.POST(path, { params: { path: { id: customerId } }, body: { reason: reason.trim() } }))
      })
      setOpen(false)
      setReason('')
      onChanged(detail)
    } catch (err) {
      if (err instanceof ApiCallError && err.code === 'MFA_ENROLMENT_REQUIRED') setNeedsSetup(true)
      setError(err instanceof ApiCallError ? err.message : 'Could not change this customer.')
    } finally {
      setBusy(false)
    }
  }

  if (!open) {
    return (
      <div className="customer-action">
        <button type="button" onClick={() => setOpen(true)}>{label}</button>
      </div>
    )
  }
  return (
    <div className="customer-action">
      <p className="muted">
        {action === 'suspend'
          ? 'The customer is signed out everywhere and cannot book until re-activated.'
          : 'The customer will be able to sign in and book again.'}
      </p>
      <label>
        Reason (required, kept in the audit log)
        <input type="text" maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} />
      </label>
      <div className="row-actions">
        <button type="button" onClick={confirm} disabled={busy || !reason.trim()}>
          {label}
        </button>
        <button type="button" onClick={() => { setOpen(false); setError(null) }} disabled={busy}>
          Cancel
        </button>
      </div>
      {error && <p role="alert">{error}</p>}
      {needsSetup && <p><Link to="/security">Set up your authenticator</Link> first.</p>}
    </div>
  )
}

export default function CustomerDetail() {
  const { customerId } = useParams<{ customerId: string }>()
  const { role } = useSession()
  const [detail, setDetail] = useState<Detail | null>(null)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(() => {
    if (!customerId) return
    setError(null)
    client
      .GET('/api/v1/admin/customers/{id}', { params: { path: { id: customerId } } })
      .then((result) => setDetail(unwrap(result)))
      .catch((err: unknown) => setError(err instanceof ApiCallError ? err.message : 'Failed to load customer.'))
  }, [customerId])

  useEffect(() => load(), [load])

  if (error) return <p role="alert">{error}</p>
  if (!detail) return <p className="muted">Loading…</p>

  const canAct = role === 'ADMIN' || role === 'SUPER_ADMIN'
  const rides = detail.rides ?? []
  return (
    <section>
      <p><Link to="/customers">← Customers</Link></p>
      <h1>{detail.name || 'Unnamed customer'}</h1>
      <p><StatusBadge status={detail.status} /></p>
      <dl className="customer-facts">
        <div><dt>Phone</dt><dd>{detail.phoneMasked ?? '—'}</dd></div>
        <div><dt>Joined</dt><dd>{detail.createdAt ? new Date(detail.createdAt).toLocaleDateString() : '—'}</dd></div>
        <div><dt>Last sign-in</dt><dd>{detail.lastLoginAt ? new Date(detail.lastLoginAt).toLocaleString() : 'Never'}</dd></div>
        <div><dt>Total rides</dt><dd>{detail.totalRides}</dd></div>
        <div><dt>Completed</dt><dd>{detail.completedRides}</dd></div>
        <div><dt>Cancelled</dt><dd>{detail.cancelledRides}</dd></div>
      </dl>

      {canAct && customerId && <StatusAction customerId={customerId} status={detail.status} onChanged={setDetail} />}

      <h2>Ride history</h2>
      {rides.length === 0 ? (
        <p className="muted">No rides yet.</p>
      ) : (
        <div className="table-scroll">
          <table className="list-table stack-table">
            <thead>
              <tr><th>Date</th><th>Status</th><th>Fare</th></tr>
            </thead>
            <tbody>
              {rides.map((ride) => (
                <tr key={ride.bookingId}>
                  <td data-label="Date">{ride.createdAt ? new Date(ride.createdAt).toLocaleString() : '—'}</td>
                  <td data-label="Status"><Link to={`/rides/${ride.bookingId}`}>{ride.status}</Link></td>
                  <td data-label="Fare">{fare(ride.finalFare ?? ride.estimatedFare, ride.currency)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}
