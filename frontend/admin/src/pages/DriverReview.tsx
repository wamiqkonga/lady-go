import { useCallback, useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { client, unwrap, expectOk, ApiCallError } from '../lib/client'
import type { components } from '../lib/api-types'
import DocumentViewer from '../components/DocumentViewer'
import { ConfirmDialog, ReasonDialog } from '../components/ReasonDialog'
import { documentLabel, documentStatusLabel, requirementOf, REQUIREMENT_LABELS } from '../lib/documents'
import { NAME_MISSING, verificationLabel } from '../lib/driverStatus'

type Review = components['schemas']['DriverReviewSummaryResponse']
type DocumentRow = components['schemas']['DriverDocumentAdminResponse']

/** Captains register with an RC photo and a selfie; the licence and fitness certificate are optional (see lib/documents). */
const EXPECTED_DOCUMENTS = ['VEHICLE_REGISTRATION', 'SELFIE', 'LICENSE', 'FITNESS_CERTIFICATE']

function RequirementBadge({ type }: { type: string | undefined }) {
  const requirement = requirementOf(type)
  return <span className={`req-badge ${requirement}`}>{REQUIREMENT_LABELS[requirement]}</span>
}

function DocumentRowView({ doc, onReviewed }: { doc: DocumentRow; onReviewed: () => void }) {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [rejecting, setRejecting] = useState(false)
  const requirement = requirementOf(doc.type)

  async function approve() {
    if (!doc.id) return
    setBusy(true)
    setError(null)
    try {
      expectOk(await client.POST('/api/v1/admin/driver-documents/{id}/approve', { params: { path: { id: doc.id } } }))
      onReviewed()
    } catch (err) {
      setError(err instanceof ApiCallError ? err.message : 'Failed to approve.')
    } finally {
      setBusy(false)
    }
  }

  async function reject(note: string) {
    if (!doc.id) return
    setBusy(true)
    setError(null)
    try {
      expectOk(
        await client.POST('/api/v1/admin/driver-documents/{id}/reject', { params: { path: { id: doc.id } }, body: { note } }),
      )
      setRejecting(false)
      onReviewed()
    } catch (err) {
      setRejecting(false)
      setError(err instanceof ApiCallError ? err.message : 'Failed to reject.')
    } finally {
      setBusy(false)
    }
  }

  const expired = Boolean(doc.expiresAt) && new Date(doc.expiresAt!) < new Date()
  const label = documentLabel(doc.type)

  return (
    <li className={`document-row${requirement === 'not-required' ? ' doc-not-required' : ''}`}>
      <div>
        <strong>{label}</strong> <RequirementBadge type={doc.type} />{' '}
        <span className="muted">({documentStatusLabel(doc.status)})</span>
        {doc.expiresAt && (
          <span className={expired ? 'badge-expired' : 'muted'}> · expires {doc.expiresAt}{expired ? ' (expired)' : ''}</span>
        )}
      </div>
      {doc.reviewNote && (
        // React escapes text children by construction - this is never dangerouslySetInnerHTML, so a
        // reviewer's note containing markup (even hostile markup) renders as literal text, never runs.
        <p className="muted review-note">Note: {doc.reviewNote}</p>
      )}
      {doc.id && <DocumentViewer documentId={doc.id} title={label} />}
      {doc.status === 'PENDING' && requirement !== 'not-required' && (
        <div className="row-actions">
          <button type="button" className="btn-primary" onClick={approve} disabled={busy}>
            Approve
          </button>
          <button type="button" onClick={() => setRejecting(true)} disabled={busy}>
            Reject
          </button>
        </div>
      )}
      {doc.status === 'PENDING' && requirement === 'not-required' && (
        <p className="muted review-note">Two-wheeler captains no longer need this, so there is nothing to approve.</p>
      )}
      {error && <p role="alert">{error}</p>}
      {rejecting && (
        <ReasonDialog
          title={`Reject ${label}`}
          description="The captain sees this reason and can upload a new photo."
          confirmLabel="Reject document"
          requiredMessage="Write a reason so the captain knows what to fix."
          onConfirm={reject}
          onCancel={() => setRejecting(false)}
        />
      )}
    </li>
  )
}

function formatWhen(iso: string | undefined): string {
  return iso ? new Date(iso).toLocaleString() : 'an unknown time'
}

type Pending = null | 'approve-driver' | 'reject-driver' | 'reject-vehicle'

export default function DriverReview() {
  const { driverId } = useParams<{ driverId: string }>()
  const [review, setReview] = useState<Review | null>(null)
  // loadError replaces the page (nothing to show); actionError is a failed approve/reject and must
  // leave the review on screen so the reviewer can read it and retry.
  const [loadError, setLoadError] = useState<string | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [pending, setPending] = useState<Pending>(null)

  const load = useCallback(() => {
    if (!driverId) return Promise.resolve()
    return client
      .GET('/api/v1/admin/drivers/{id}/review', { params: { path: { id: driverId } } })
      .then((result) => setReview(unwrap(result)))
      .catch((err: unknown) => setLoadError(err instanceof ApiCallError ? err.message : 'Failed to load.'))
  }, [driverId])

  useEffect(() => {
    void load()
  }, [load])

  async function runAction(failureMessage: string, successMessage: string, action: () => Promise<void>) {
    setBusy(true)
    setActionError(null)
    setNotice(null)
    try {
      await action()
      setPending(null)
      await load()
      setNotice(successMessage)
    } catch (err) {
      setPending(null)
      setActionError(err instanceof ApiCallError ? err.message : failureMessage)
    } finally {
      setBusy(false)
    }
  }

  function approveVehicle(vehicleId: string) {
    return runAction('Failed to approve vehicle.', 'Vehicle approved.', async () => {
      expectOk(await client.POST('/api/v1/admin/vehicles/{id}/approve', { params: { path: { id: vehicleId } } }))
    })
  }

  function rejectVehicle(vehicleId: string, note: string) {
    return runAction('Failed to reject vehicle.', 'Vehicle rejected.', async () => {
      expectOk(
        await client.POST('/api/v1/admin/vehicles/{id}/reject', { params: { path: { id: vehicleId } }, body: { note } }),
      )
    })
  }

  function approveDriver() {
    if (!driverId) return
    return runAction('Failed to approve driver.', 'Driver approved. They can now go online and take rides.', async () => {
      expectOk(await client.POST('/api/v1/admin/drivers/{id}/approve', { params: { path: { id: driverId } } }))
    })
  }

  function rejectDriver(note: string) {
    if (!driverId) return
    return runAction('Failed to reject driver.', 'Driver rejected. They have been told why.', async () => {
      expectOk(await client.POST('/api/v1/admin/drivers/{id}/reject', { params: { path: { id: driverId } }, body: { note } }))
    })
  }

  if (loadError) return <p role="alert">{loadError}</p>
  if (!review) return <p className="muted">Loading…</p>

  const status = review.verificationStatus
  const decider = review.decidedByName ?? review.decidedByEmailMasked ?? 'an admin'
  const decided = status === 'APPROVED' || status === 'REJECTED' || status === 'SUSPENDED'
  const vehicleId = review.vehicle?.id

  return (
    <section>
      <p><Link to="/driver-verification">← Verification</Link></p>
      <h1>Driver review</h1>
      <p className="muted">
        {review.driverName ?? NAME_MISSING} {review.driverPhoneMasked ? `· ${review.driverPhoneMasked}` : ''} ·{' '}
        <strong>{verificationLabel(status)}</strong>
      </p>
      {notice && <p className="success" role="status">{notice}</p>}
      {actionError && <p role="alert">{actionError}</p>}

      {review.vehicle && (
        <div className="panel">
          <h2>Vehicle</h2>
          <p>
            {review.vehicle.registrationNumber} ({review.vehicle.vehicleType}) · {review.vehicle.verificationStatus}
          </p>
          {review.vehicle.verificationStatus === 'PENDING' && vehicleId && (
            <div className="row-actions">
              <button type="button" className="btn-primary" onClick={() => approveVehicle(vehicleId)} disabled={busy}>
                Approve vehicle
              </button>
              <button type="button" onClick={() => setPending('reject-vehicle')} disabled={busy}>
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
          {EXPECTED_DOCUMENTS.filter((type) => !review.documents?.some((d) => d.type === type)).map((type) => (
            <li key={type} className="document-row">
              <div>
                <strong>{documentLabel(type)}</strong> <RequirementBadge type={type} />{' '}
                <span className="muted">(not provided{requirementOf(type) === 'optional' ? ' - optional' : ''})</span>
              </div>
            </li>
          ))}
        </ul>
      </div>

      <div className="panel" aria-labelledby="decision-heading">
        <h2 id="decision-heading">Decision</h2>
        {status === 'APPROVED' && (
          <p className="outcome outcome-approved">Approved on {formatWhen(review.decidedAt)} by {decider}.</p>
        )}
        {status === 'REJECTED' && (
          <p className="outcome outcome-rejected">
            Rejected on {formatWhen(review.decidedAt)} by {decider}. Reason: {review.rejectionReason ?? 'not recorded'}
          </p>
        )}
        {status === 'SUSPENDED' && (
          <p className="outcome outcome-rejected">
            This driver is suspended. See <Link to={`/drivers/${review.driverId}`}>the driver page</Link> for who suspended them and why.
          </p>
        )}
        {!decided && (
          <>
            {review.readyForApproval ? (
              <p>Ready for approval - every requirement is met.</p>
            ) : (
              <div>
                <p>Not ready yet:</p>
                <ul>{review.missingRequirements?.map((m) => <li key={m}>{m}</li>)}</ul>
              </div>
            )}
            <div className="row-actions">
              <button type="button" className="btn-primary" onClick={() => setPending('approve-driver')} disabled={busy || !review.readyForApproval}>
                Approve driver
              </button>
              <button type="button" onClick={() => setPending('reject-driver')} disabled={busy}>
                Reject driver
              </button>
            </div>
          </>
        )}
      </div>

      {pending === 'approve-driver' && (
        <ConfirmDialog
          title="Approve this driver?"
          description={`${review.driverName ?? 'This captain'} will be able to go online and take rides. This is hard to undo: you would have to suspend them.`}
          confirmLabel="Yes, approve driver"
          onConfirm={approveDriver}
          onCancel={() => setPending(null)}
        />
      )}
      {pending === 'reject-driver' && (
        <ReasonDialog
          title="Reject this driver"
          description="The captain is told this reason. Rejecting is hard to undo."
          confirmLabel="Reject driver"
          requiredMessage="Write a reason so the captain knows what to fix."
          onConfirm={rejectDriver}
          onCancel={() => setPending(null)}
        />
      )}
      {pending === 'reject-vehicle' && vehicleId && (
        <ReasonDialog
          title="Reject this vehicle"
          confirmLabel="Reject vehicle"
          requiredMessage="Write a reason so the captain knows what to fix."
          onConfirm={(note) => rejectVehicle(vehicleId, note)}
          onCancel={() => setPending(null)}
        />
      )}
    </section>
  )
}
