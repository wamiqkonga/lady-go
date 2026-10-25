import { useCallback, useEffect, useState } from 'react'
import { useParams } from 'react-router-dom'
import { client, unwrap, ApiCallError } from '../lib/client'
import type { components } from '../lib/api-types'

type Detail = components['schemas']['AdminRideDetailResponse']
type FlagRow = components['schemas']['AdminRideFlagResponse']

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
      <input type="text" placeholder="Review note (required)" value={note} onChange={(e) => setNote(e.target.value)} />
      <button type="button" onClick={submit} disabled={busy || !note.trim()}>
        Mark reviewed
      </button>
      {error && <p role="alert">{error}</p>}
    </div>
  )
}

export default function RideDetail() {
  const { bookingId } = useParams<{ bookingId: string }>()
  const [detail, setDetail] = useState<Detail | null>(null)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(() => {
    if (!bookingId) return
    setError(null)
    client
      .GET('/api/v1/admin/rides/{bookingId}', { params: { path: { bookingId } } })
      .then((result) => setDetail(unwrap(result)))
      .catch((err: unknown) => setError(err instanceof ApiCallError ? err.message : 'Failed to load ride.'))
  }, [bookingId])

  useEffect(() => load(), [load])

  if (error) return <p role="alert">{error}</p>
  if (!detail) return <p className="muted">Loading…</p>

  return (
    <section>
      <h1>Ride detail</h1>
      <p className="muted">{detail.bookingId} · {detail.bookingStatus}</p>

      <div className="panel">
        <h2>Participants</h2>
        <p>Customer: {detail.customer?.name ?? '—'} ({detail.customer?.phoneMasked ?? '—'})</p>
        <p>Driver: {detail.driver?.name ?? '—'} ({detail.driver?.phoneMasked ?? '—'})</p>
      </div>

      <div className="panel">
        <h2>Pickup &amp; fare</h2>
        <p>Pickup: {detail.pickupAddress ?? '—'}</p>
        <p>
          Fare: {detail.finalFare ?? detail.estimatedFare ?? '—'} · Payment: {detail.paymentMethod ?? '—'}
        </p>
        {detail.paymentMethod === 'CASH' && (
          <p className="muted">
            Cash confirmed: {detail.cashConfirmedAt ?? 'not yet'} · Disputed: {detail.cashDisputedAt ?? 'no'}
          </p>
        )}
        {detail.cancelReason && (
          <p className="muted">
            Cancelled by {detail.cancelledBy ?? 'unknown'}: {detail.cancelReason}
          </p>
        )}
      </div>

      <div className="panel">
        <h2>Flags</h2>
        {!detail.flags || detail.flags.length === 0 ? (
          <p className="muted">No flags on this ride.</p>
        ) : (
          <ul className="document-list">
            {detail.flags.map((flag) => (
              <li key={flag.id} className="document-row">
                <div>
                  <strong>{flag.type}</strong>
                </div>
                {flag.riderResponse && <p className="muted">Rider response: {flag.riderResponse}</p>}
                {flag.adminReviewedAt ? (
                  <p className="review-note">Reviewed: {flag.adminReviewNote}</p>
                ) : (
                  <FlagReviewForm flag={flag} onReviewed={load} />
                )}
              </li>
            ))}
          </ul>
        )}
      </div>

      <div className="panel">
        <h2>Timeline</h2>
        {!detail.timeline || detail.timeline.length === 0 ? (
          <p className="muted">No events recorded.</p>
        ) : (
          <div className="table-scroll">
            <table className="list-table">
              <thead>
                <tr>
                  <th>When</th>
                  <th>From</th>
                  <th>To</th>
                  <th>Actor</th>
                  <th>Reason</th>
                </tr>
              </thead>
              <tbody>
                {detail.timeline.map((event, i) => (
                  <tr key={i}>
                    <td>{event.createdAt ? new Date(event.createdAt).toLocaleString() : '—'}</td>
                    <td>{event.fromStatus ?? '—'}</td>
                    <td>{event.toStatus ?? '—'}</td>
                    <td>{event.actorType ?? '—'}</td>
                    <td>{event.reason ?? '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </section>
  )
}
