import { useEffect, useState } from 'react'
import Page from '../components/Page'
import { API_BASE_URL } from '../config'

type TripShare = {
  driverFirstName: string | null
  vehicleMake: string | null
  vehicleModel: string | null
  vehicleColor: string | null
  vehicleRegistration: string | null
  latitude: number | null
  longitude: number | null
  locationAt: string | null
}

type View = { kind: 'loading' } | { kind: 'ok'; share: TripShare } | { kind: 'gone' } | { kind: 'error' }

const REFRESH_MS = 10_000

/** The token lives in the URL fragment: browsers never send it to a server or put it in an access log. */
function tokenFromHash(hash: string): string {
  return hash.replace(/^#/, '').trim()
}

async function lookup(token: string): Promise<View> {
  try {
    const response = await fetch(`${API_BASE_URL}/api/v1/trip-shares/lookup`, {
      headers: { 'X-Trip-Share-Token': token },
      cache: 'no-store',
    })
    // The server answers every dead link the same way, so all 4xx mean "no longer active".
    if (response.status >= 400 && response.status < 500) return { kind: 'gone' }
    if (!response.ok) return { kind: 'error' }
    const body = (await response.json()) as { data?: TripShare }
    return body.data ? { kind: 'ok', share: body.data } : { kind: 'error' }
  } catch {
    return { kind: 'error' }
  }
}

function describeVehicle(share: TripShare): string {
  return [share.vehicleColor, share.vehicleMake, share.vehicleModel].filter(Boolean).join(' ')
}

function ago(iso: string | null, now: number): string | null {
  if (!iso) return null
  const seconds = Math.max(0, Math.round((now - Date.parse(iso)) / 1000))
  if (Number.isNaN(seconds)) return null
  if (seconds < 60) return 'just now'
  return `${Math.round(seconds / 60)} min ago`
}

export default function TrackTrip() {
  const [token] = useState(() => tokenFromHash(window.location.hash))
  const [view, setView] = useState<View>(token ? { kind: 'loading' } : { kind: 'gone' })
  const [now, setNow] = useState(() => Date.now())

  useEffect(() => {
    if (!token) return
    let cancelled = false
    const refresh = async () => {
      const next = await lookup(token)
      if (cancelled) return
      setNow(Date.now())
      // A brief network blip keeps the last good position on screen instead of replacing it with an error.
      setView((previous) => (next.kind === 'error' && previous.kind === 'ok' ? previous : next))
    }
    void refresh()
    const timer = window.setInterval(() => void refresh(), REFRESH_MS)
    return () => {
      cancelled = true
      window.clearInterval(timer)
    }
  }, [token])

  return (
    <Page title="Follow a trip" icon="share" lead="Someone shared their Lady Go ride with you so you can see it is going well.">
      {view.kind === 'loading' && <p role="status">Finding the trip…</p>}
      {view.kind === 'gone' && (
        <p role="alert">This link is no longer active. Trip links stop working shortly after the ride ends.</p>
      )}
      {view.kind === 'error' && <p role="alert">We could not load the trip just now. We will keep trying.</p>}
      {view.kind === 'ok' && <TripDetails share={view.share} now={now} />}
      <p className="note">
        You can see the driver&rsquo;s first name, her vehicle and where she is. You cannot see anything else about the
        rider.
      </p>
    </Page>
  )
}

function TripDetails({ share, now }: { share: TripShare; now: number }) {
  const updated = ago(share.locationAt, now)
  const hasLocation = share.latitude !== null && share.longitude !== null
  return (
    <section aria-label="Trip details">
      <h2>{share.driverFirstName ? `${share.driverFirstName} is driving` : 'Your contact is on a ride'}</h2>
      <p>
        {describeVehicle(share) || 'Vehicle details are not available'}
        {share.vehicleRegistration ? ` · ${share.vehicleRegistration}` : ''}
      </p>
      {hasLocation ? (
        <>
          <p>Last seen {updated ?? 'a moment ago'}.</p>
          <a
            className="button"
            href={`https://www.google.com/maps?q=${share.latitude},${share.longitude}`}
            target="_blank"
            rel="noopener noreferrer"
          >
            See her on the map
          </a>
        </>
      ) : (
        <p>The driver&rsquo;s location is not available right now.</p>
      )}
    </section>
  )
}
