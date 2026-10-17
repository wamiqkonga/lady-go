import { useCallback, useEffect, useState } from 'react'
import { client, unwrap, ApiCallError } from '../lib/client'
import { useSession } from '../lib/sessionContext'
import DocumentUploadSection from '../components/DocumentUploadSection'
import SelfieCapture from '../components/SelfieCapture'
import type { components } from '../lib/api-types'

type DriverProfileResponse = components['schemas']['DriverProfileResponse']
type VehicleResponse = components['schemas']['VehicleResponse']
type DriverDocumentResponse = components['schemas']['DriverDocumentResponse']
type DeclarationStatusResponse = components['schemas']['DeclarationStatusResponse']
type CityResponse = { id?: string; name?: string; country?: string }
type DocumentType = NonNullable<DriverDocumentResponse['type']>

const REQUIRED_DOCUMENTS: { type: DocumentType; label: string; needsNumber?: boolean; needsExpiry?: boolean }[] = [
  { type: 'LICENSE', label: 'Driving licence', needsNumber: true, needsExpiry: true },
  { type: 'VEHICLE_REGISTRATION', label: 'Vehicle registration (RC) photo' },
  { type: 'VEHICLE_PHOTO', label: 'Vehicle photo (plate visible)' },
  { type: 'INSURANCE', label: 'Insurance', needsExpiry: true },
  { type: 'FITNESS_CERTIFICATE', label: 'Fitness certificate' },
  { type: 'POLLUTION_CERTIFICATE', label: 'Pollution certificate' },
]

function overallBadge(status?: string) {
  if (status === 'APPROVED') return <span className="badge badge-ok">Approved</span>
  if (status === 'REJECTED') return <span className="badge badge-bad">Rejected</span>
  if (status === 'SUSPENDED') return <span className="badge badge-bad">Suspended</span>
  if (status === 'PENDING_REVIEW') return <span className="badge badge-warn">Pending review</span>
  return <span className="badge badge-warn">Not submitted</span>
}

function CityPicker({ onBootstrapped }: { onBootstrapped: (profile: DriverProfileResponse) => void }) {
  const [cities, setCities] = useState<CityResponse[] | null>(null)
  const [cityId, setCityId] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    client.GET('/api/v1/cities').then((result) => setCities(unwrap(result) as CityResponse[]))
  }, [])

  async function submit() {
    if (!cityId) return
    setBusy(true)
    setError(null)
    try {
      const result = await client.POST('/api/v1/drivers/me', { body: { cityId } })
      onBootstrapped(unwrap(result))
    } catch (err) {
      setError(err instanceof ApiCallError ? err.message : 'Failed to start your application.')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="section">
      <h2>Which city will you drive in?</h2>
      {cities === null ? (
        <p className="muted">Loading…</p>
      ) : (
        <div className="form">
          <select value={cityId} onChange={(e) => setCityId(e.target.value)}>
            <option value="">Select a city</option>
            {cities.map((city) => (
              <option key={city.id} value={city.id}>{city.name}</option>
            ))}
          </select>
          {error && <p role="alert">{error}</p>}
          <button type="button" onClick={submit} disabled={busy || !cityId}>
            Start application
          </button>
        </div>
      )}
    </div>
  )
}

export default function Dashboard() {
  const { signOut } = useSession()
  const [profile, setProfile] = useState<DriverProfileResponse | null>(null)
  const [vehicle, setVehicle] = useState<VehicleResponse | null>(null)
  const [documents, setDocuments] = useState<DriverDocumentResponse[] | null>(null)
  const [declaration, setDeclaration] = useState<DeclarationStatusResponse | null>(null)
  const [vehicleForm, setVehicleForm] = useState({ registrationNumber: '', vehicleType: 'CAB', make: '', model: '', color: '' })
  const [vehicleError, setVehicleError] = useState<string | null>(null)
  const [declarationBusy, setDeclarationBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  // A driver who has never called bootstrapProfile has no Driver row at all - GET /drivers/me 404s,
  // not "NOT_SUBMITTED" - so this is a distinct, expected state from a real load failure, not an
  // error. Caught by actually running the app and logging in as a brand-new driver (no mocked test
  // ever exercises a real 404 here); every mocked component test stubs a profile response and so
  // never reaches this path.
  const [noProfileYet, setNoProfileYet] = useState(false)

  const load = useCallback(() => {
    setError(null)
    setNoProfileYet(false)
    client.GET('/api/v1/drivers/me')
      .then((result) => setProfile(unwrap(result)))
      .catch((err: unknown) => {
        if (err instanceof ApiCallError && err.status === 404) {
          setNoProfileYet(true)
          return
        }
        setError(err instanceof ApiCallError ? err.message : 'Failed to load your profile.')
      })
  }, [])

  useEffect(() => load(), [load])

  const loadRest = useCallback(() => {
    client.GET('/api/v1/drivers/me/vehicle').then((result) => {
      try { setVehicle(unwrap(result)) } catch { setVehicle(null) }
    })
    client.GET('/api/v1/drivers/me/documents').then((result) => setDocuments(unwrap(result) ?? []))
    client.GET('/api/v1/drivers/me/declaration').then((result) => setDeclaration(unwrap(result)))
  }, [])

  useEffect(() => {
    if (profile) loadRest()
  }, [profile, loadRest])

  /** The backend flips verificationStatus to PENDING_REVIEW on a driver's first meaningful action
   *  (vehicle/document/declaration - DriverService.markPendingReviewIfNotSubmitted), so the status
   *  banner has to be re-fetched after any of them, not just once at bootstrap - caught by actually
   *  uploading a document in a real browser and watching the banner stay stuck on "Not submitted"
   *  (no mocked test exercises the real sequence of calls this depends on). */
  function refreshProfileStatus() {
    client.GET('/api/v1/drivers/me').then((result) => setProfile(unwrap(result))).catch(() => {})
  }

  async function submitVehicle() {
    setVehicleError(null)
    try {
      const result = await client.PUT('/api/v1/drivers/me/vehicle', {
        body: {
          registrationNumber: vehicleForm.registrationNumber,
          vehicleType: vehicleForm.vehicleType as 'CAB' | 'SUV' | 'AUTO' | 'BIKE',
          make: vehicleForm.make || undefined,
          model: vehicleForm.model || undefined,
          color: vehicleForm.color || undefined,
        },
      })
      setVehicle(unwrap(result))
      refreshProfileStatus()
    } catch (err) {
      setVehicleError(err instanceof ApiCallError ? err.message : 'Failed to save the vehicle.')
    }
  }

  async function acceptDeclaration() {
    if (!declaration) return
    setDeclarationBusy(true)
    try {
      await client.POST('/api/v1/drivers/me/declaration', { body: { declarationVersion: declaration.currentVersion! } })
      client.GET('/api/v1/drivers/me/declaration').then((result) => setDeclaration(unwrap(result)))
      refreshProfileStatus()
    } catch {
      // The section itself re-renders with the same "not accepted" state - no separate error banner needed for a single button.
    } finally {
      setDeclarationBusy(false)
    }
  }

  function documentFor(type: DocumentType): DriverDocumentResponse | undefined {
    return documents?.filter((d) => d.type === type).sort((a, b) => (b.createdAt ?? '').localeCompare(a.createdAt ?? ''))[0]
  }

  function onDocumentUploaded(doc: DriverDocumentResponse) {
    setDocuments((prev) => [...(prev ?? []), doc])
    refreshProfileStatus()
  }

  if (error) return <p role="alert">{error}</p>
  if (!profile && !noProfileYet) return <p className="muted">Loading…</p>

  return (
    <div className="app-shell">
      <div className="topbar">
        <span className="brand">Driver registration</span>
        <button type="button" onClick={signOut}>Sign out</button>
      </div>
      <div className="content">
        <h1>My application</h1>
        {profile && (
          <div className="section">
            <h2>Application status</h2>
            <p>{overallBadge(profile.verificationStatus)}</p>
          </div>
        )}

        {!profile ? (
          <CityPicker onBootstrapped={setProfile} />
        ) : (
          <>
            <div className="section">
              <h2>Vehicle</h2>
              {vehicle ? (
                <p>
                  {vehicle.registrationNumber} ({vehicle.vehicleType}) · {overallBadge(vehicle.verificationStatus)}
                </p>
              ) : (
                <div className="form">
                  <input
                    type="text"
                    placeholder="Registration number"
                    value={vehicleForm.registrationNumber}
                    onChange={(e) => setVehicleForm({ ...vehicleForm, registrationNumber: e.target.value })}
                  />
                  <select
                    value={vehicleForm.vehicleType}
                    onChange={(e) => setVehicleForm({ ...vehicleForm, vehicleType: e.target.value })}
                  >
                    <option value="CAB">Cab</option>
                    <option value="SUV">SUV</option>
                    <option value="AUTO">Auto</option>
                    <option value="BIKE">Bike</option>
                  </select>
                  <input
                    type="text"
                    placeholder="Make (optional)"
                    value={vehicleForm.make}
                    onChange={(e) => setVehicleForm({ ...vehicleForm, make: e.target.value })}
                  />
                  <input
                    type="text"
                    placeholder="Model (optional)"
                    value={vehicleForm.model}
                    onChange={(e) => setVehicleForm({ ...vehicleForm, model: e.target.value })}
                  />
                  {vehicleError && <p role="alert">{vehicleError}</p>}
                  <button type="button" onClick={submitVehicle} disabled={!vehicleForm.registrationNumber.trim()}>
                    Save vehicle
                  </button>
                </div>
              )}
            </div>

            {REQUIRED_DOCUMENTS.map((doc) => (
              <DocumentUploadSection
                key={doc.type}
                type={doc.type}
                label={doc.label}
                needsNumber={doc.needsNumber}
                needsExpiry={doc.needsExpiry}
                existing={documentFor(doc.type)}
                onUploaded={onDocumentUploaded}
              />
            ))}

            <SelfieCapture existing={documentFor('SELFIE')} onUploaded={onDocumentUploaded} />

            <div className="section">
              <h2>Self-declaration</h2>
              {declaration?.accepted ? (
                <p className="badge badge-ok">Accepted (version {declaration.acceptedVersion})</p>
              ) : declaration ? (
                <>
                  <p className="muted">{declaration.currentText}</p>
                  <div className="row-actions">
                    <button type="button" onClick={acceptDeclaration} disabled={declarationBusy}>
                      I agree
                    </button>
                  </div>
                </>
              ) : (
                <p className="muted">Loading…</p>
              )}
            </div>
          </>
        )}
      </div>
    </div>
  )
}
