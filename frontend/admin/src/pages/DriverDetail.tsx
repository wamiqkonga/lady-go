import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { Link, useParams } from 'react-router-dom'
import { client, unwrap, expectOk, ApiCallError } from '../lib/client'
import { useStepUp } from '../lib/stepUpContext'
import { useSession } from '../lib/sessionContext'
import type { components } from '../lib/api-types'

type Driver = components['schemas']['AdminDriverDetailResponse']

/** Whole rupees from minor units (ADR-007). */
function money(minor: number | undefined): string {
  return `₹${((minor ?? 0) / 100).toFixed(2)}`
}

type Action = 'suspend' | 'activate'

function ActionPanel({ driver, onDone }: { driver: Driver; onDone: () => void }) {
  const { runWithStepUp } = useStepUp()
  const [open, setOpen] = useState(false)
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [needsSetup, setNeedsSetup] = useState(false)

  const action: Action | null =
    driver.verificationStatus === 'APPROVED' ? 'suspend' : driver.verificationStatus === 'SUSPENDED' ? 'activate' : null
  if (!action || !driver.id) return null
  const id = driver.id

  async function submit(e: FormEvent) {
    e.preventDefault()
    if (!reason.trim()) {
      setError('Enter a reason - it is saved in the audit log.')
      return
    }
    setBusy(true)
    setError(null)
    setNeedsSetup(false)
    try {
      await runWithStepUp(async () => {
        const path = { id }
        const body = { reason: reason.trim() }
        expectOk(
          action === 'suspend'
            ? await client.POST('/api/v1/admin/drivers/{id}/suspend', { params: { path }, body })
            : await client.POST('/api/v1/admin/drivers/{id}/activate', { params: { path }, body }),
        )
      })
      setOpen(false)
      setReason('')
      onDone()
    } catch (err) {
      if (err instanceof ApiCallError && err.code === 'MFA_ENROLMENT_REQUIRED') setNeedsSetup(true)
      setError(err instanceof ApiCallError ? err.message : 'The action failed. Nothing was changed.')
    } finally {
      setBusy(false)
    }
  }

  const label = action === 'suspend' ? 'Suspend driver' : 'Re-activate driver'
  return (
    <div className="panel">
      <h2>Account</h2>
      {!open ? (
        <div className="row-actions">
          <button type="button" onClick={() => setOpen(true)}>
            {label}
          </button>
        </div>
      ) : (
        <form className="form" onSubmit={submit}>
          <p className="muted">
            {action === 'suspend'
              ? 'The driver is forced offline and receives no ride offers until re-activated.'
              : 'The driver is approved again but stays offline until they go online themselves.'}
          </p>
          <label htmlFor="driver-action-reason">Reason</label>
          <textarea
            id="driver-action-reason"
            rows={3}
            maxLength={500}
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            disabled={busy}
          />
          <div className="row-actions">
            <button type="submit" disabled={busy}>
              {busy ? 'Working…' : `Confirm ${action}`}
            </button>
            <button type="button" onClick={() => { setOpen(false); setError(null) }} disabled={busy}>
              Cancel
            </button>
          </div>
        </form>
      )}
      {error && <p role="alert">{error}</p>}
      {needsSetup && (
        <p>
          <Link to="/security">Set up your authenticator</Link> first.
        </p>
      )}
    </div>
  )
}

export default function DriverDetail() {
  const { driverId } = useParams<{ driverId: string }>()
  const { role } = useSession()
  const [driver, setDriver] = useState<Driver | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)

  const load = useCallback(() => {
    if (!driverId) return
    client
      .GET('/api/v1/admin/drivers/{id}', { params: { path: { id: driverId } } })
      .then((result) => setDriver(unwrap(result)))
      .catch((err: unknown) => setLoadError(err instanceof ApiCallError ? err.message : 'Failed to load this driver.'))
  }, [driverId])

  useEffect(() => load(), [load])

  if (loadError) return <p role="alert">{loadError}</p>
  if (!driver) return <p className="muted">Loading…</p>

  const docs = driver.documents
  const canAct = role === 'ADMIN' || role === 'SUPER_ADMIN' // cosmetic: the backend enforces it.
  return (
    <section>
      <p>
        <Link to="/drivers">← Drivers</Link>
      </p>
      <h1>{driver.name ?? driver.id}</h1>
      <p className="muted">
        {driver.phoneMasked ?? '—'} · {driver.verificationStatus} · {driver.availabilityStatus} · rating{' '}
        {driver.ratingAvg?.toFixed(2)} ({driver.ratingCount})
      </p>
      {driver.rejectionReason && <p className="muted">Rejection reason: {driver.rejectionReason}</p>}

      {canAct && <ActionPanel driver={driver} onDone={load} />}

      <div className="panel">
        <h2>Vehicle</h2>
        {driver.vehicle ? (
          <p>
            {driver.vehicle.registrationNumber} ({driver.vehicle.vehicleType}) · {driver.vehicle.verificationStatus}
          </p>
        ) : (
          <p className="muted">No vehicle on file.</p>
        )}
      </div>

      <div className="panel">
        <h2>Documents</h2>
        <p>
          {docs?.total ?? 0} uploaded · {docs?.approved ?? 0} approved · {docs?.pending ?? 0} pending ·{' '}
          {docs?.rejected ?? 0} rejected
        </p>
        {docs?.reviewPath && <Link to={docs.reviewPath}>Open document review</Link>}
      </div>

      <div className="panel">
        <h2>Earnings</h2>
        <p>
          {driver.earnings?.rides ?? 0} rides · gross {money(driver.earnings?.gross)} · commission{' '}
          {money(driver.earnings?.commission)} · net {money(driver.earnings?.net)}
        </p>
      </div>

      <div className="panel">
        <h2>Recent rides</h2>
        {driver.recentRides?.length ? (
          <ul className="document-list">
            {driver.recentRides.map((ride) => (
              <li key={ride.bookingId} className="document-row">
                <Link to={`/rides/${ride.bookingId}`}>{ride.createdAt ? new Date(ride.createdAt).toLocaleString() : ride.bookingId}</Link>{' '}
                <span className="muted">
                  {ride.status}
                  {ride.finalFare != null ? ` · ${money(ride.finalFare)}` : ''}
                </span>
              </li>
            ))}
          </ul>
        ) : (
          <p className="muted">No rides yet.</p>
        )}
      </div>
    </section>
  )
}
