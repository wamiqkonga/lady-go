import { useCallback, useEffect, useState } from 'react'
import { useParams } from 'react-router-dom'
import { client, unwrap, ApiCallError } from '../lib/client'
import type { components } from '../lib/api-types'

type Review = components['schemas']['DriverReviewSummaryResponse']
type DocumentRow = components['schemas']['DriverDocumentAdminResponse']

function DocumentViewer({ documentId }: { documentId: string }) {
  const [url, setUrl] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  const load = () => {
    setError(null)
    client
      .GET('/api/v1/admin/driver-documents/{id}/view', { params: { path: { id: documentId } } })
      .then((result) => setUrl(unwrap(result)))
      .catch((err: unknown) => setError(err instanceof ApiCallError ? err.message : 'Failed to load image.'))
  }

  if (error) return <p role="alert">{error}</p>
  if (!url) {
    return (
      <button type="button" onClick={load}>
        View
      </button>
    )
  }
  // The signed URL expires quickly (verification.document.view_url.ttl.seconds, default 60s) - not
  // cached, re-fetched fresh every time "View" is clicked. The image itself is a watermarked
  // throwaway derivative, never the original (docs/product/admin-web-design.md section 2).
  return <img src={url} alt="Document" className="document-preview" />
}

function DocumentRowView({ doc, onReviewed }: { doc: DocumentRow; onReviewed: () => void }) {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function approve() {
    if (!doc.id) return
    setBusy(true)
    setError(null)
    try {
      await client.POST('/api/v1/admin/driver-documents/{id}/approve', { params: { path: { id: doc.id } } })
      onReviewed()
    } catch (err) {
      setError(err instanceof ApiCallError ? err.message : 'Failed to approve.')
    } finally {
      setBusy(false)
    }
  }

  async function reject() {
    if (!doc.id) return
    const note = window.prompt('Reason for rejecting this document:')
    if (!note) return
    setBusy(true)
    setError(null)
    try {
      await client.POST('/api/v1/admin/driver-documents/{id}/reject', {
        params: { path: { id: doc.id } },
        body: { note },
      })
      onReviewed()
    } catch (err) {
      setError(err instanceof ApiCallError ? err.message : 'Failed to reject.')
    } finally {
      setBusy(false)
    }
  }

  const expired = Boolean(doc.expiresAt) && new Date(doc.expiresAt!) < new Date()

  return (
    <li className="document-row">
      <div>
        <strong>{doc.type}</strong> <span className="muted">({doc.status})</span>
        {doc.expiresAt && (
          <span className={expired ? 'badge-expired' : 'muted'}> · expires {doc.expiresAt}{expired ? ' (expired)' : ''}</span>
        )}
      </div>
      {doc.id && <DocumentViewer documentId={doc.id} />}
      {doc.status === 'PENDING' && (
        <div className="row-actions">
          <button type="button" onClick={approve} disabled={busy}>
            Approve
          </button>
          <button type="button" onClick={reject} disabled={busy}>
            Reject
          </button>
        </div>
      )}
      {error && <p role="alert">{error}</p>}
    </li>
  )
}

export default function DriverReview() {
  const { driverId } = useParams<{ driverId: string }>()
  const [review, setReview] = useState<Review | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const load = useCallback(() => {
    if (!driverId) return
    client
      .GET('/api/v1/admin/drivers/{id}/review', { params: { path: { id: driverId } } })
      .then((result) => setReview(unwrap(result)))
      .catch((err: unknown) => setError(err instanceof ApiCallError ? err.message : 'Failed to load.'))
  }, [driverId])

  useEffect(() => load(), [load])

  async function approveVehicle(vehicleId: string) {
    await client.POST('/api/v1/admin/vehicles/{id}/approve', { params: { path: { id: vehicleId } } })
    load()
  }

  async function rejectVehicle(vehicleId: string) {
    const note = window.prompt('Reason for rejecting this vehicle:')
    if (!note) return
    await client.POST('/api/v1/admin/vehicles/{id}/reject', { params: { path: { id: vehicleId } }, body: { note } })
    load()
  }

  async function approveDriver() {
    if (!driverId) return
    setBusy(true)
    setError(null)
    try {
      await client.POST('/api/v1/admin/drivers/{id}/approve', { params: { path: { id: driverId } } })
      load()
    } catch (err) {
      setError(err instanceof ApiCallError ? err.message : 'Failed to approve driver.')
    } finally {
      setBusy(false)
    }
  }

  async function rejectDriver() {
    if (!driverId) return
    const note = window.prompt('Reason for rejecting this driver:')
    if (!note) return
    setBusy(true)
    setError(null)
    try {
      await client.POST('/api/v1/admin/drivers/{id}/reject', { params: { path: { id: driverId } }, body: { note } })
      load()
    } catch (err) {
      setError(err instanceof ApiCallError ? err.message : 'Failed to reject driver.')
    } finally {
      setBusy(false)
    }
  }

  if (error) return <p role="alert">{error}</p>
  if (!review) return <p className="muted">Loading…</p>

  return (
    <section>
      <h1>Driver review</h1>
      <p className="muted">
        {review.driverName ?? review.driverId} {review.driverPhoneMasked ? `· ${review.driverPhoneMasked}` : ''} ·{' '}
        {review.verificationStatus}
      </p>

      {review.vehicle && (
        <div className="panel">
          <h2>Vehicle</h2>
          <p>
            {review.vehicle.registrationNumber} ({review.vehicle.vehicleType}) · {review.vehicle.verificationStatus}
          </p>
          {review.vehicle.verificationStatus === 'PENDING' && review.vehicle.id && (
            <div className="row-actions">
              <button type="button" onClick={() => approveVehicle(review.vehicle!.id!)}>
                Approve vehicle
              </button>
              <button type="button" onClick={() => rejectVehicle(review.vehicle!.id!)}>
                Reject vehicle
              </button>
            </div>
          )}
        </div>
      )}

      <div className="panel">
        <h2>Documents</h2>
        <ul className="document-list">
          {review.documents?.map((doc) => <DocumentRowView key={doc.id} doc={doc} onReviewed={load} />)}
        </ul>
      </div>

      <div className="panel">
        <h2>Decision</h2>
        {review.readyForApproval ? (
          <p>Ready for approval - every requirement is met.</p>
        ) : (
          <div>
            <p>Not ready yet:</p>
            <ul>{review.missingRequirements?.map((m) => <li key={m}>{m}</li>)}</ul>
          </div>
        )}
        <div className="row-actions">
          <button type="button" onClick={approveDriver} disabled={busy || !review.readyForApproval}>
            Approve driver
          </button>
          <button type="button" onClick={rejectDriver} disabled={busy}>
            Reject driver
          </button>
        </div>
      </div>
    </section>
  )
}
