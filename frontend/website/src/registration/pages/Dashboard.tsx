import { useCallback, useEffect, useRef, useState } from 'react'
import { client, unwrap, ApiCallError } from '../lib/client'
import { useSession } from '../lib/sessionContext'
import DocumentUploadSection from '../components/DocumentUploadSection'
import SelfieCapture from '../components/SelfieCapture'
import {
  STEPS, WORK_STEPS, countDone, firstIncompleteIndex, latestDocument, stepState,
  type RegistrationData, type Step,
} from '../lib/steps'
import type { components } from '../lib/api-types'

type DriverProfileResponse = components['schemas']['DriverProfileResponse']
type VehicleResponse = components['schemas']['VehicleResponse']
type DriverDocumentResponse = components['schemas']['DriverDocumentResponse']
type DeclarationStatusResponse = components['schemas']['DeclarationStatusResponse']
type CityResponse = { id?: string; name?: string; country?: string }

const VEHICLE_TYPES = [
  { value: 'CAB', label: 'Cab' },
  { value: 'SUV', label: 'SUV' },
  { value: 'AUTO', label: 'Auto' },
  { value: 'BIKE', label: 'Bike' },
] as const

/** Overall status. The backend moves a captain to PENDING_REVIEW on their first upload, long before
 *  the application is complete, so "Under review" is only shown once every step is actually in. */
function statusBadge(status: string | undefined, allDone: boolean) {
  if (status === 'APPROVED') return <span className="badge badge-ok">Approved</span>
  if (status === 'REJECTED') return <span className="badge badge-bad">Rejected</span>
  if (status === 'SUSPENDED') return <span className="badge badge-bad">Suspended</span>
  if (allDone) return <span className="badge badge-ok">Under review</span>
  return <span className="badge badge-warn">In progress</span>
}

function CityPicker({ onBootstrapped }: { onBootstrapped: (profile: DriverProfileResponse) => void }) {
  const [cities, setCities] = useState<CityResponse[] | null>(null)
  const [cityId, setCityId] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    client.GET('/api/v1/cities')
      .then((result) => setCities(unwrap(result) as CityResponse[]))
      .catch(() => setError('Could not load the list of cities. Check your connection and refresh the page.'))
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
      <p className="muted">Registration takes about 10 minutes. Keep your licence, RC, insurance and certificates nearby.</p>
      {cities === null && !error ? (
        <p className="muted">Loading…</p>
      ) : (
        <div className="form">
          <label htmlFor="city">City</label>
          <select id="city" value={cityId} onChange={(e) => setCityId(e.target.value)}>
            <option value="">Select a city</option>
            {(cities ?? []).map((city) => (
              <option key={city.id} value={city.id}>{city.name}</option>
            ))}
          </select>
          {error && <p role="alert" className="error">{error}</p>}
          <button type="button" onClick={submit} disabled={busy || !cityId}>
            Start application
          </button>
        </div>
      )}
    </div>
  )
}

function ProgressHeader({ index, done }: { index: number; done: number }) {
  const total = WORK_STEPS.length
  const step = STEPS[index]
  return (
    <div className="progress">
      <div
        className="progress-track"
        role="progressbar"
        aria-label="Registration progress"
        aria-valuemin={0}
        aria-valuemax={total}
        aria-valuenow={done}
        aria-valuetext={`${done} of ${total} steps done`}
      >
        <div className="progress-fill" style={{ width: `${(done / total) * 100}%` }} />
      </div>
      <p className="muted progress-label">
        {step.kind === 'review' ? `All steps · ${done} of ${total} done` : `Step ${index + 1} of ${total} · ${done} done`}
      </p>
    </div>
  )
}

function StepList({ data, current, onGo }: { data: RegistrationData; current: number; onGo: (index: number) => void }) {
  // Open by default on wide screens, where the list sits in a side column; collapsed on phones.
  const [open, setOpen] = useState(() => typeof window !== 'undefined' && !!window.matchMedia?.('(min-width: 960px)').matches)
  return (
    <details className="step-list" open={open} onToggle={(e) => setOpen(e.currentTarget.open)}>
      <summary>All steps</summary>
      <ol>
        {STEPS.map((step, i) => {
          if (step.kind === 'review') return null
          const state = stepState(step, data)
          return (
            <li key={step.id}>
              <button type="button" className="step-link" onClick={() => onGo(i)} aria-current={i === current ? 'step' : undefined}>
                <span className={`step-dot step-${state}`} aria-hidden="true" />
                <span>{step.title}</span>
                <span className="muted">{state === 'done' ? 'Done' : state === 'fix' ? 'Needs a new photo' : 'To do'}</span>
              </button>
            </li>
          )
        })}
      </ol>
    </details>
  )
}

export default function Dashboard() {
  const { signOut } = useSession()
  const [profile, setProfile] = useState<DriverProfileResponse | null>(null)
  const [vehicle, setVehicle] = useState<VehicleResponse | null>(null)
  const [vehicleLoaded, setVehicleLoaded] = useState(false)
  const [documents, setDocuments] = useState<DriverDocumentResponse[] | null>(null)
  const [declaration, setDeclaration] = useState<DeclarationStatusResponse | null>(null)
  const [vehicleForm, setVehicleForm] = useState({ registrationNumber: '', vehicleType: 'CAB', make: '', model: '', color: '' })
  const [vehicleError, setVehicleError] = useState<string | null>(null)
  const [vehicleBusy, setVehicleBusy] = useState(false)
  const [declarationBusy, setDeclarationBusy] = useState(false)
  const [declarationRead, setDeclarationRead] = useState(false)
  const [declarationError, setDeclarationError] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [stepIndex, setStepIndex] = useState(0)
  const positioned = useRef(false)
  // A captain who has never called bootstrapProfile has no Captain row at all - GET /drivers/me 404s,
  // not "NOT_SUBMITTED" - so this is a distinct, expected state from a real load failure, not an
  // error. Caught by actually running the app and logging in as a brand-new captain (no mocked test
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
    setLoadError(null)
    // 404 means "no vehicle saved yet" (an expected state); any other failure is a real load error and
    // must not look like an empty vehicle form, or the captain could overwrite a vehicle they already have.
    client.GET('/api/v1/drivers/me/vehicle').then((result) => {
      try {
        setVehicle(unwrap(result))
      } catch (err) {
        if (!(err instanceof ApiCallError && err.status === 404)) throw err
        setVehicle(null)
      }
      setVehicleLoaded(true)
    }).catch(() => setLoadError('Could not load your vehicle.'))
    client.GET('/api/v1/drivers/me/documents').then((result) => setDocuments(unwrap(result) ?? []))
      .catch(() => setLoadError('Could not load your documents.'))
    client.GET('/api/v1/drivers/me/declaration').then((result) => setDeclaration(unwrap(result)))
      .catch(() => setLoadError('Could not load the declaration.'))
  }, [])

  useEffect(() => {
    if (profile) loadRest()
  }, [profile, loadRest])

  const data: RegistrationData = { vehicle, documents: documents ?? [], declaration }
  const ready = profile !== null && vehicleLoaded && documents !== null && declaration !== null

  // Resume: once everything has loaded, jump to the first step the captain still has to do. Runs once,
  // so later edits never yank the captain to a different screen.
  useEffect(() => {
    if (ready && !positioned.current) {
      positioned.current = true
      setStepIndex(firstIncompleteIndex({ vehicle, documents: documents ?? [], declaration }))
    }
  }, [ready, vehicle, documents, declaration])

  /** The backend flips verificationStatus to PENDING_REVIEW on a captain's first meaningful action
   *  (vehicle/document/declaration - DriverService.markPendingReviewIfNotSubmitted), so the status
   *  banner has to be re-fetched after any of them, not just once at bootstrap - caught by actually
   *  uploading a document in a real browser and watching the banner stay stuck on "Not submitted"
   *  (no mocked test exercises the real sequence of calls this depends on). */
  function refreshProfileStatus() {
    client.GET('/api/v1/drivers/me').then((result) => setProfile(unwrap(result))).catch(() => {})
  }

  async function submitVehicle() {
    setVehicleError(null)
    setVehicleBusy(true)
    try {
      const result = await client.PUT('/api/v1/drivers/me/vehicle', {
        body: {
          registrationNumber: vehicleForm.registrationNumber.trim().toUpperCase(),
          vehicleType: vehicleForm.vehicleType as 'CAB' | 'SUV' | 'AUTO' | 'BIKE',
          make: vehicleForm.make.trim() || undefined,
          model: vehicleForm.model.trim() || undefined,
          color: vehicleForm.color.trim() || undefined,
        },
      })
      setVehicle(unwrap(result))
      setVehicleLoaded(true)
      refreshProfileStatus()
    } catch (err) {
      setVehicleError(err instanceof ApiCallError ? err.message : 'Failed to save the vehicle.')
    } finally {
      setVehicleBusy(false)
    }
  }

  async function acceptDeclaration() {
    if (!declaration) return
    setDeclarationBusy(true)
    setDeclarationError(null)
    try {
      unwrap(await client.POST('/api/v1/drivers/me/declaration', { body: { declarationVersion: declaration.currentVersion! } }))
      // The accept already succeeded, so show it as accepted right away; the re-fetch only brings in
      // server-side details and a failure of it must not undo or hide the acceptance.
      setDeclaration({ ...declaration, accepted: true, acceptedVersion: declaration.currentVersion })
      client.GET('/api/v1/drivers/me/declaration').then((result) => setDeclaration(unwrap(result))).catch(() => {})
      refreshProfileStatus()
    } catch (err) {
      setDeclarationError(err instanceof ApiCallError ? err.message : 'Could not save your agreement. Please try again.')
    } finally {
      setDeclarationBusy(false)
    }
  }

  function onDocumentUploaded(doc: DriverDocumentResponse) {
    setDocuments((prev) => [...(prev ?? []), doc])
    refreshProfileStatus()
  }

  if (error) return <p role="alert">{error}</p>
  if (!profile && !noProfileYet) return <p className="muted">Loading…</p>

  const done = countDone(data)
  const allDone = done === WORK_STEPS.length
  const step: Step = STEPS[stepIndex]
  const isReview = step.kind === 'review'
  const canContinue = isReview ? false : stepState(step, data) === 'done'

  function renderVehicleStep() {
    if (vehicle) {
      return (
        <div className="section">
          <h2>Your vehicle</h2>
          <p>
            {vehicle.registrationNumber} ({vehicle.vehicleType}){' '}
            <span className={`badge ${vehicle.verificationStatus === 'APPROVED' ? 'badge-ok' : 'badge-warn'}`}>
              {vehicle.verificationStatus === 'APPROVED' ? 'Approved' : 'Saved'}
            </span>
          </p>
        </div>
      )
    }
    return (
      <form
        className="section form"
        onSubmit={(e) => {
          e.preventDefault()
          submitVehicle()
        }}
      >
        <h2>Your vehicle</h2>
        <p className="muted">Tell us about the vehicle you will drive.</p>
        <label htmlFor="v-reg">Registration number</label>
        <input
          id="v-reg"
          type="text"
          autoCapitalize="characters"
          autoComplete="off"
          placeholder="e.g. JK01AB1234"
          value={vehicleForm.registrationNumber}
          onChange={(e) => setVehicleForm({ ...vehicleForm, registrationNumber: e.target.value })}
        />
        <fieldset className="type-cards">
          <legend>Vehicle type</legend>
          {VEHICLE_TYPES.map((t) => (
            <label key={t.value} className={`type-card ${vehicleForm.vehicleType === t.value ? 'selected' : ''}`}>
              <input
                type="radio"
                name="vehicleType"
                value={t.value}
                checked={vehicleForm.vehicleType === t.value}
                onChange={() => setVehicleForm({ ...vehicleForm, vehicleType: t.value })}
              />
              {t.label}
            </label>
          ))}
        </fieldset>
        <label htmlFor="v-make">Make <span className="muted">(optional)</span></label>
        <input id="v-make" type="text" placeholder="e.g. Maruti" value={vehicleForm.make}
          onChange={(e) => setVehicleForm({ ...vehicleForm, make: e.target.value })} />
        <label htmlFor="v-model">Model <span className="muted">(optional)</span></label>
        <input id="v-model" type="text" placeholder="e.g. Dzire" value={vehicleForm.model}
          onChange={(e) => setVehicleForm({ ...vehicleForm, model: e.target.value })} />
        <label htmlFor="v-color">Colour <span className="muted">(optional)</span></label>
        <input id="v-color" type="text" placeholder="e.g. White" value={vehicleForm.color}
          onChange={(e) => setVehicleForm({ ...vehicleForm, color: e.target.value })} />
        {vehicleError && <p role="alert" className="error">{vehicleError}</p>}
        <button type="submit" disabled={vehicleBusy || !vehicleForm.registrationNumber.trim()}>
          {vehicleBusy ? 'Saving…' : 'Save vehicle'}
        </button>
      </form>
    )
  }

  function renderDeclarationStep() {
    return (
      <div className="section">
        <h2>Self-declaration</h2>
        {declaration?.accepted ? (
          <p className="badge badge-ok">Accepted (version {declaration.acceptedVersion})</p>
        ) : declaration ? (
          <>
            <p className="muted">Please read this before you agree.</p>
            <div className="declaration-text" tabIndex={0} aria-label="Declaration text">{declaration.currentText}</div>
            <label className="check">
              <input type="checkbox" checked={declarationRead} onChange={(e) => setDeclarationRead(e.target.checked)} />
              I have read and understood the declaration
            </label>
            {declarationError && <p role="alert" className="error">{declarationError}</p>}
            <div className="row-actions">
              <button type="button" onClick={acceptDeclaration} disabled={declarationBusy || !declarationRead}>
                I agree
              </button>
            </div>
          </>
        ) : (
          <p className="muted">Loading…</p>
        )}
      </div>
    )
  }

  function renderReviewStep() {
    return (
      <div className="section">
        {allDone && (profile?.verificationStatus === 'APPROVED' || profile?.verificationStatus === 'REJECTED' || profile?.verificationStatus === 'SUSPENDED') ? (
          <>
            <h2>{profile.verificationStatus === 'APPROVED' ? 'You are approved' : 'Application update'}</h2>
            <p>{statusBadge(profile.verificationStatus, allDone)}</p>
            <p>
              {profile.verificationStatus === 'APPROVED'
                ? 'Your application has been approved. Open the Lady Go captain app to start driving.'
                : 'Your application is not active. Check the notes on each document below, or contact support.'}
            </p>
          </>
        ) : allDone ? (
          <>
            <h2>Application received</h2>
            <p>{statusBadge(profile?.verificationStatus, allDone)}</p>
            <p>Thank you. Our team will check your documents next.</p>
            <ul className="next-steps">
              <li>You do not need to do anything else right now.</li>
              <li>Come back to this page any time to see the status of each document.</li>
              <li>If we cannot accept a document, we will tell you why here, and you can send a new photo.</li>
            </ul>
          </>
        ) : (
          <>
            <h2>Almost there</h2>
            <p>{statusBadge(profile?.verificationStatus, allDone)}</p>
            <p>Your application is only reviewed once every step below is done.</p>
          </>
        )}
        <ul className="review-list">
          {WORK_STEPS.map((s) => {
            const state = stepState(s, data)
            const doc = s.kind === 'document' || s.kind === 'selfie' ? latestDocument(data.documents, s.id) : undefined
            return (
              <li key={s.id}>
                <span className={`step-dot step-${state}`} aria-hidden="true" />
                <span>{s.title}</span>
                <span className="muted">
                  {state === 'done' ? (doc?.status === 'APPROVED' ? 'Approved' : 'Done') : state === 'fix' ? 'Needs a new photo' : 'To do'}
                </span>
                {state !== 'done' && (
                  <button type="button" className="link" onClick={() => setStepIndex(STEPS.indexOf(s))}>
                    {state === 'fix' ? 'Fix' : 'Do now'}
                  </button>
                )}
              </li>
            )
          })}
        </ul>
      </div>
    )
  }

  function renderStep() {
    switch (step.kind) {
      case 'vehicle':
        return renderVehicleStep()
      case 'document':
        return (
          <DocumentUploadSection
            key={step.id}
            type={step.id}
            label={step.title}
            tip={step.tip}
            needsNumber={step.needsNumber}
            needsExpiry={step.needsExpiry}
            existing={latestDocument(data.documents, step.id)}
            onUploaded={onDocumentUploaded}
          />
        )
      case 'selfie':
        return <SelfieCapture existing={latestDocument(data.documents, 'SELFIE')} onUploaded={onDocumentUploaded} />
      case 'declaration':
        return renderDeclarationStep()
      case 'review':
        return renderReviewStep()
    }
  }

  return (
    <div className="app-shell">
      <div className="topbar">
        <span className="brand">Captain registration</span>
        <span className="topbar-actions">
          <button type="button" onClick={signOut}>Sign out</button>
        </span>
      </div>
      <div className="content">
        <h1>My application</h1>
        {!profile ? (
          <CityPicker onBootstrapped={setProfile} />
        ) : (
          <>
            <div className="section status-row">
              <h2>Application status</h2>
              <p>{statusBadge(profile.verificationStatus, allDone)}</p>
            </div>
            {loadError && (
              <p role="alert" className="error">
                {loadError} <button type="button" className="link" onClick={loadRest}>Try again</button>
              </p>
            )}
            {!ready ? (
              <p className="muted">Loading…</p>
            ) : (
              <div className="reg-grid">
                <div className="reg-side">
                  <ProgressHeader index={stepIndex} done={done} />
                  <StepList data={data} current={stepIndex} onGo={setStepIndex} />
                </div>
                <div className="reg-main">{renderStep()}</div>
              </div>
            )}
          </>
        )}
      </div>
      {profile && ready && (
        <div className="primary-action">
          <div className="action-bar">
            {stepIndex > 0 && (
              <button type="button" className="secondary" onClick={() => setStepIndex(stepIndex - 1)}>
                Back
              </button>
            )}
            {!isReview && (
              <button
                type="button"
                onClick={() => setStepIndex(stepIndex + 1)}
                disabled={!canContinue}
                aria-describedby="continue-hint"
              >
                Continue
              </button>
            )}
          </div>
          {!isReview && !canContinue && (
            <p id="continue-hint" className="muted action-hint">Finish this step to continue.</p>
          )}
        </div>
      )}
    </div>
  )
}
