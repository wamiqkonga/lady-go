import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { client, unwrap, ApiCallError } from '../lib/client'
import type { components } from '../lib/api-types'

type RideRow = components['schemas']['AdminRideSummaryResponse']
type FlagRow = components['schemas']['AdminRideFlagResponse']
type BookingStatus = NonNullable<RideRow['bookingStatus']>
type FlagType = NonNullable<FlagRow['type']>

const BOOKING_STATUSES: BookingStatus[] = [
  'REQUESTED', 'SEARCHING_DRIVER', 'DRIVER_ASSIGNED', 'DRIVER_ACCEPTED', 'DRIVER_ARRIVING',
  'DRIVER_ARRIVED', 'RIDE_STARTED', 'RIDE_COMPLETED', 'PAYMENT_PENDING', 'COMPLETED', 'CANCELLED',
]
const FLAG_TYPES: FlagType[] = [
  'EARLY_END', 'COMPLETION_FAR_FROM_DESTINATION', 'PIN_LOCKOUT', 'CASH_DISPUTE', 'RIDER_SAFETY_CANCEL',
  'RIDER_CONFIRMED_ARRIVAL',
]
// These two types are the ones most likely to need the founder's actual attention during a live
// pilot (docs/product/admin-web-design.md section 4), so they're visually called out in the list.
const ATTENTION_FLAG_TYPES = new Set<FlagType>(['RIDER_SAFETY_CANCEL', 'CASH_DISPUTE'])

function RidesTab() {
  const [rows, setRows] = useState<RideRow[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [status, setStatus] = useState('')
  const [hasOpenFlags, setHasOpenFlags] = useState('')
  const [createdAfter, setCreatedAfter] = useState('')
  const [createdBefore, setCreatedBefore] = useState('')

  const load = useCallback(() => {
    setError(null)
    client
      .GET('/api/v1/admin/rides', {
        params: {
          query: {
            status: status ? (status as BookingStatus) : undefined,
            hasOpenFlags: hasOpenFlags ? hasOpenFlags === 'true' : undefined,
            createdAfter: createdAfter ? new Date(createdAfter).toISOString() : undefined,
            createdBefore: createdBefore ? new Date(createdBefore).toISOString() : undefined,
            pageable: { page: 0, size: 50, sort: ['createdAt,desc'] },
          },
        },
      })
      .then((result) => setRows(unwrap(result).content ?? []))
      .catch((err: unknown) => setError(err instanceof ApiCallError ? err.message : 'Failed to load rides.'))
  }, [status, hasOpenFlags, createdAfter, createdBefore])

  useEffect(() => load(), [load])

  return (
    <div>
      <div className="filters">
        <label>
          Status
          <select value={status} onChange={(e) => setStatus(e.target.value)}>
            <option value="">All</option>
            {BOOKING_STATUSES.map((s) => (
              <option key={s} value={s}>{s}</option>
            ))}
          </select>
        </label>
        <label>
          Flagged
          <select value={hasOpenFlags} onChange={(e) => setHasOpenFlags(e.target.value)}>
            <option value="">All</option>
            <option value="true">Has open flags</option>
            <option value="false">No open flags</option>
          </select>
        </label>
        <label>
          From
          <input type="date" value={createdAfter} onChange={(e) => setCreatedAfter(e.target.value)} />
        </label>
        <label>
          To
          <input type="date" value={createdBefore} onChange={(e) => setCreatedBefore(e.target.value)} />
        </label>
      </div>

      {error && <p role="alert">{error}</p>}
      {!error && rows === null && <p className="muted">Loading…</p>}
      {!error && rows !== null && rows.length === 0 && <p className="muted">No rides match these filters.</p>}
      {!error && rows !== null && rows.length > 0 && (
        <div className="table-scroll">
          <table className="list-table">
            <thead>
              <tr>
                <th>Created</th>
                <th>Status</th>
                <th>Customer</th>
                <th>Driver</th>
                <th>Fare</th>
                <th>Open flags</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => (
                <tr key={row.bookingId}>
                  <td>{row.createdAt ? new Date(row.createdAt).toLocaleString() : '—'}</td>
                  <td>
                    <Link to={`/rides/${row.bookingId}`}>{row.bookingStatus}</Link>
                  </td>
                  <td>{row.customer?.name ?? '—'} {row.customer?.phoneMasked ? `(${row.customer.phoneMasked})` : ''}</td>
                  <td>{row.driver?.name ?? '—'} {row.driver?.phoneMasked ? `(${row.driver.phoneMasked})` : ''}</td>
                  <td>{row.finalFare ?? row.estimatedFare ?? '—'}</td>
                  <td>
                    {row.openFlagCount && row.openFlagCount > 0 ? (
                      <span className="badge-expired">{row.openFlagCount}</span>
                    ) : (
                      0
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}

function FlagReviewForm({ flag, onReviewed }: { flag: FlagRow; onReviewed: () => void }) {
  const [note, setNote] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function submit() {
    if (!flag.id || !note.trim()) return
    setBusy(true)
    setError(null)
    try {
      const result = await client.POST('/api/v1/admin/ride-flags/{id}/review', {
        params: { path: { id: flag.id } },
        body: { note },
      })
      unwrap(result)
      onReviewed()
    } catch (err) {
      setError(err instanceof ApiCallError ? err.message : 'Failed to review this flag.')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="row-actions">
      <input
        type="text"
        placeholder="Review note (required)"
        value={note}
        onChange={(e) => setNote(e.target.value)}
      />
      <button type="button" onClick={submit} disabled={busy || !note.trim()}>
        Mark reviewed
      </button>
      {error && <p role="alert">{error}</p>}
    </div>
  )
}

function FlagsTab() {
  const [rows, setRows] = useState<FlagRow[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [type, setType] = useState('')
  const [reviewed, setReviewed] = useState('false')

  const load = useCallback(() => {
    setError(null)
    client
      .GET('/api/v1/admin/ride-flags', {
        params: {
          query: {
            type: type ? (type as FlagType) : undefined,
            reviewed: reviewed ? reviewed === 'true' : undefined,
            pageable: { page: 0, size: 50, sort: ['createdAt,desc'] },
          },
        },
      })
      .then((result) => setRows(unwrap(result).content ?? []))
      .catch((err: unknown) => setError(err instanceof ApiCallError ? err.message : 'Failed to load flags.'))
  }, [type, reviewed])

  useEffect(() => load(), [load])

  return (
    <div>
      <div className="filters">
        <label>
          Type
          <select value={type} onChange={(e) => setType(e.target.value)}>
            <option value="">All</option>
            {FLAG_TYPES.map((t) => (
              <option key={t} value={t}>{t}</option>
            ))}
          </select>
        </label>
        <label>
          Review state
          <select value={reviewed} onChange={(e) => setReviewed(e.target.value)}>
            <option value="false">Unreviewed</option>
            <option value="true">Reviewed</option>
            <option value="">All</option>
          </select>
        </label>
      </div>

      {error && <p role="alert">{error}</p>}
      {!error && rows === null && <p className="muted">Loading…</p>}
      {!error && rows !== null && rows.length === 0 && <p className="muted">No flags match these filters.</p>}
      {!error && rows !== null && rows.length > 0 && (
        <ul className="document-list">
          {rows.map((flag) => (
            <li
              key={flag.id}
              className={ATTENTION_FLAG_TYPES.has(flag.type as FlagType) ? 'document-row flag-attention' : 'document-row'}
            >
              <div>
                <strong>{flag.type}</strong>{' '}
                <span className="muted">
                  · ride <Link to={`/rides/${flag.bookingId}`}>{flag.bookingId?.slice(0, 8)}…</Link>
                </span>
              </div>
              <p className="muted">
                Customer: {flag.customer?.name ?? '—'} ({flag.customer?.phoneMasked ?? '—'}) · Driver:{' '}
                {flag.driver?.name ?? '—'} ({flag.driver?.phoneMasked ?? '—'})
              </p>
              {flag.riderResponse && <p className="muted">Rider response: {flag.riderResponse}</p>}
              {flag.adminReviewedAt ? (
                // React escapes text children by construction - never dangerouslySetInnerHTML, so a
                // hostile note renders as literal text, never runs.
                <p className="review-note">Reviewed: {flag.adminReviewNote}</p>
              ) : (
                <FlagReviewForm flag={flag} onReviewed={load} />
              )}
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

export default function RideOversight() {
  const [tab, setTab] = useState<'rides' | 'flags'>('rides')

  return (
    <section>
      <h1>Ride &amp; flag oversight</h1>
      <div className="tabs">
        <button type="button" className={tab === 'rides' ? 'tab active' : 'tab'} onClick={() => setTab('rides')}>
          Rides
        </button>
        <button type="button" className={tab === 'flags' ? 'tab active' : 'tab'} onClick={() => setTab('flags')}>
          Flags
        </button>
      </div>
      {tab === 'rides' ? <RidesTab /> : <FlagsTab />}
    </section>
  )
}
