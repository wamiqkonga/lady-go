import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import LiveMapCanvas, { type MapMarker } from '../components/LiveMapCanvas'
import { ApiCallError, client, unwrap } from '../lib/client'
import type { components } from '../lib/api-types'

type Snapshot = components['schemas']['AdminLiveMapResponse']
type Driver = components['schemas']['LiveDriver']
type Ride = components['schemas']['LiveRide']
type Incident = components['schemas']['LiveIncident']

export const POLL_MS = 10_000
/** A position older than this is still shown (the server drops it at admin.livemap.max_age.minutes) but flagged. */
export const STALE_AFTER_SECONDS = 120

type Selection = { kind: 'driver' | 'ride' | 'incident'; id: string } | null

function age(seconds: number): string {
  if (seconds < 60) return `${seconds}s ago`
  const minutes = Math.floor(seconds / 60)
  return minutes < 60 ? `${minutes} min ago` : `${Math.floor(minutes / 60)} h ${minutes % 60} min ago`
}

function pretty(value: string | undefined): string {
  return (value ?? '').toLowerCase().replaceAll('_', ' ')
}

export default function LiveMap() {
  const [snapshot, setSnapshot] = useState<Snapshot | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [selection, setSelection] = useState<Selection>(null)
  const inFlight = useRef(false)

  const load = useCallback(async () => {
    if (inFlight.current) return
    inFlight.current = true
    try {
      setSnapshot(unwrap(await client.GET('/api/v1/admin/live-map')))
      setError(null)
    } catch (err: unknown) {
      setError(err instanceof ApiCallError ? err.message : 'Could not load the live map.')
    } finally {
      inFlight.current = false
    }
  }, [])

  // Poll every 10 s, but not while the tab is hidden; refresh straight away when it comes back.
  useEffect(() => {
    let timer: ReturnType<typeof setInterval> | undefined
    const start = () => {
      if (timer === undefined) timer = setInterval(() => void load(), POLL_MS)
    }
    const stop = () => {
      if (timer !== undefined) clearInterval(timer)
      timer = undefined
    }
    const onVisibility = () => {
      if (document.hidden) {
        stop()
      } else {
        void load()
        start()
      }
    }
    if (!document.hidden) {
      void load()
      start()
    }
    document.addEventListener('visibilitychange', onVisibility)
    return () => {
      stop()
      document.removeEventListener('visibilitychange', onVisibility)
    }
  }, [load])

  const drivers = useMemo(() => snapshot?.drivers ?? [], [snapshot])
  const rides = useMemo(() => snapshot?.rides ?? [], [snapshot])
  const incidents = useMemo(() => snapshot?.incidents ?? [], [snapshot])

  const markers = useMemo<MapMarker[]>(() => {
    const out: MapMarker[] = []
    for (const d of drivers) {
      out.push({
        id: `driver:${d.driverId}`, kind: d.availability === 'ONLINE' ? 'driver' : 'driver-busy',
        lat: d.latitude ?? 0, lon: d.longitude ?? 0, label: `${d.name ?? 'Driver'} (${pretty(d.availability)})`,
        stale: (d.ageSeconds ?? 0) > STALE_AFTER_SECONDS,
      })
    }
    for (const r of rides) {
      out.push({ id: `ride:${r.bookingId}`, kind: 'pickup', lat: r.pickupLatitude ?? 0, lon: r.pickupLongitude ?? 0, label: `Pickup: ${r.pickupAddress ?? ''}` })
      if (selection?.kind === 'ride' && selection.id === r.bookingId) {
        out.push({ id: `drop:${r.bookingId}`, kind: 'drop', lat: r.dropLatitude ?? 0, lon: r.dropLongitude ?? 0, label: `Drop: ${r.dropAddress ?? ''}` })
      }
    }
    for (const i of incidents) {
      if (i.latitude != null && i.longitude != null) {
        out.push({ id: `incident:${i.incidentId}`, kind: 'incident', lat: i.latitude, lon: i.longitude, label: `${pretty(i.type)} incident (${pretty(i.severity)})` })
      }
    }
    return out
  }, [drivers, rides, incidents, selection])

  const onMarkerSelect = useCallback((markerId: string) => {
    const [kind, id] = markerId.split(':')
    if (kind === 'driver' || kind === 'ride' || kind === 'incident') setSelection({ kind, id })
    else if (kind === 'drop') setSelection({ kind: 'ride', id })
  }, [])

  const selectedId = selection ? `${selection.kind}:${selection.id}` : null
  const selectedDriver = selection?.kind === 'driver' ? drivers.find((d) => d.driverId === selection.id) : undefined
  const selectedRide = selection?.kind === 'ride' ? rides.find((r) => r.bookingId === selection.id) : undefined
  const selectedIncident = selection?.kind === 'incident' ? incidents.find((i) => i.incidentId === selection.id) : undefined

  const empty = snapshot !== null && drivers.length === 0 && rides.length === 0 && incidents.length === 0
  const truncated = snapshot && (snapshot.driversTruncated || snapshot.ridesTruncated || snapshot.incidentsTruncated)
  const staleDrivers = drivers.filter((d) => (d.ageSeconds ?? 0) > STALE_AFTER_SECONDS).length

  return (
    <div className="live-map-page">
      <h1>Live map</h1>
      <p className="muted">
        Last known positions only, refreshed every 10 seconds. Positions older than{' '}
        {snapshot?.maxAgeMinutes ?? 10} minutes are hidden.
      </p>

      {error && (
        <p role="alert" className="error">
          {error} {snapshot ? 'Showing the last data received.' : ''}
        </p>
      )}
      {staleDrivers > 0 && (
        <p role="status" className="warning">
          {staleDrivers} driver{staleDrivers === 1 ? '' : 's'} on the map {staleDrivers === 1 ? 'has' : 'have'} not
          reported a position for over {STALE_AFTER_SECONDS / 60} minutes. Their pin may be out of date.
        </p>
      )}
      {truncated && (
        <p role="status" className="warning">Too many items to show them all; the newest are listed.</p>
      )}
      {snapshot === null && !error && <p className="muted">Loading…</p>}
      {empty && (
        <p className="muted live-map-empty">
          Nothing to show right now: no drivers are online with a recent position, no rides are in progress and no
          safety incidents are open. This page checks again every 10 seconds.
        </p>
      )}

      <div className="live-map-layout">
        <LiveMapCanvas markers={markers} selectedId={selectedId} onSelect={onMarkerSelect} />
        <aside className="live-map-side" aria-label="Map items">
          {(selectedDriver || selectedRide || selectedIncident) && (
            <section className="panel live-map-details" aria-label="Details">
              <button type="button" className="link-button" onClick={() => setSelection(null)}>Close</button>
              {selectedDriver && <DriverDetails driver={selectedDriver} />}
              {selectedRide && <RideDetails ride={selectedRide} />}
              {selectedIncident && <IncidentDetails incident={selectedIncident} />}
            </section>
          )}

          <h2>Incidents ({incidents.length})</h2>
          <ul className="live-map-list">
            {incidents.map((i) => (
              <li key={i.incidentId}>
                <button type="button" onClick={() => setSelection({ kind: 'incident', id: i.incidentId ?? '' })}>
                  <strong className="badge-expired">{pretty(i.type)}</strong> {pretty(i.severity)} · {pretty(i.status)}
                  {i.latitude == null && <span className="muted"> · no position</span>}
                </button>
              </li>
            ))}
          </ul>

          <h2>Active rides ({rides.length})</h2>
          <ul className="live-map-list">
            {rides.map((r) => (
              <li key={r.bookingId}>
                <button type="button" onClick={() => setSelection({ kind: 'ride', id: r.bookingId ?? '' })}>
                  {pretty(r.status)} · {r.pickupAddress}
                </button>
              </li>
            ))}
          </ul>

          <h2>Drivers ({drivers.length})</h2>
          <ul className="live-map-list">
            {drivers.map((d) => (
              <li key={d.driverId}>
                <button type="button" onClick={() => setSelection({ kind: 'driver', id: d.driverId ?? '' })}>
                  {d.name ?? 'Driver'} · {pretty(d.availability)} · {age(d.ageSeconds ?? 0)}
                  {(d.ageSeconds ?? 0) > STALE_AFTER_SECONDS && <span className="badge-expired"> stale</span>}
                </button>
              </li>
            ))}
          </ul>
        </aside>
      </div>
    </div>
  )
}

function DriverDetails({ driver }: { driver: Driver }) {
  return (
    <div>
      <h3>{driver.name ?? 'Driver'}</h3>
      <p>Status: {pretty(driver.availability)}</p>
      <p>Position updated {age(driver.ageSeconds ?? 0)}</p>
      <p className="muted">{driver.latitude?.toFixed(5)}, {driver.longitude?.toFixed(5)}</p>
    </div>
  )
}

function RideDetails({ ride }: { ride: Ride }) {
  return (
    <div>
      <h3>Ride: {pretty(ride.status)}</h3>
      <p>Pickup: {ride.pickupAddress}</p>
      <p>Drop: {ride.dropAddress}</p>
      <p>Driver: {ride.driverName ?? 'not assigned'}</p>
      <p>Customer: {ride.customerName ?? 'unknown'}</p>
    </div>
  )
}

function IncidentDetails({ incident }: { incident: Incident }) {
  return (
    <div>
      <h3>{pretty(incident.type)} incident</h3>
      <p>Severity: {pretty(incident.severity)}</p>
      <p>Status: {pretty(incident.status)}</p>
      <p>Raised: {incident.createdAt ? new Date(incident.createdAt).toLocaleString() : 'unknown'}</p>
      {incident.latitude == null && <p className="muted">No position was sent with this incident.</p>}
    </div>
  )
}
