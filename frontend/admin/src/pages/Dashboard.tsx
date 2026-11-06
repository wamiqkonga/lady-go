import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { ApiCallError, client, unwrap } from '../lib/client'
import type { components } from '../lib/api-types'

type Snapshot = components['schemas']['AdminDashboardResponse']

export const REFRESH_MS = 30_000

const number = new Intl.NumberFormat('en-IN')
const rupees = new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 2 })

function hours(value: number | null | undefined): string {
  return value == null ? 'none waiting' : `${value} h`
}

function minutes(value: number | null | undefined, none: string): string {
  return value == null ? none : `${value} min`
}

type TileState = 'loading' | 'error' | 'ready'

type TileProps = {
  label: string
  state: TileState
  value?: ReactNode
  /** Accessible plain-text value; defaults to `value` when that is text. */
  valueText?: string
  note?: ReactNode
  to?: string
  /** A missed target: red, with the words "Over target" so colour is never the only signal. */
  bad?: boolean
  flag?: string
}

function Tile({ label, state, value, valueText, note, to, bad, flag = 'Over target' }: TileProps) {
  const shown = state === 'loading' ? 'Loading…' : state === 'error' ? 'Unavailable' : value
  const text = state === 'ready' ? (valueText ?? (typeof value === 'string' || typeof value === 'number' ? String(value) : '')) : shown
  const body = (
    <>
      <div className="tile-label">{label}</div>
      <div className={`tile-value${state !== 'ready' ? ' tile-value-quiet' : ''}`} aria-label={`${label}: ${text}`}>
        {shown}
      </div>
      {state === 'ready' && note && <div className="tile-note">{note}</div>}
      {state === 'ready' && bad && <div className="tile-flag">{flag}</div>}
    </>
  )
  const className = `tile${bad && state === 'ready' ? ' tile-bad' : ''}${to ? ' tile-link' : ''}`
  return to ? (
    <Link className={className} to={to} aria-busy={state === 'loading'}>
      {body}
    </Link>
  ) : (
    <div className={className} aria-busy={state === 'loading'}>
      {body}
    </div>
  )
}

function WeeklyRides({ weeks }: { weeks: components['schemas']['WeeklyRides'][] }) {
  const max = Math.max(1, ...weeks.map((w) => w.completed ?? 0))
  const total = weeks.reduce((sum, w) => sum + (w.completed ?? 0), 0)
  return (
    <div className="panel">
      <h3>Rides completed per week</h3>
      <p className="muted">Last 8 weeks (weeks start Monday, India time). {number.format(total)} in total.</p>
      <ul className="week-bars">
        {weeks.map((w) => (
          <li key={w.weekStart}>
            <span className="week-label">Week of {w.weekStart}</span>
            <span className="week-bar" aria-hidden="true">
              <span style={{ width: `${((w.completed ?? 0) / max) * 100}%`, minWidth: w.completed ? 3 : 0 }} />
            </span>
            <span className="week-count">{number.format(w.completed ?? 0)}</span>
          </li>
        ))}
      </ul>
    </div>
  )
}

export default function Dashboard() {
  const [data, setData] = useState<Snapshot | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loaded, setLoaded] = useState(false)
  const inFlight = useRef(false)

  const load = useCallback(async () => {
    if (inFlight.current) return
    inFlight.current = true
    try {
      const next = unwrap(await client.GET('/api/v1/admin/dashboard'))
      if (!next?.drivers) throw new Error('Unexpected dashboard response')
      setData(next)
      setError(null)
    } catch (err: unknown) {
      setError(err instanceof ApiCallError ? err.message : 'Could not load the dashboard.')
    } finally {
      inFlight.current = false
      setLoaded(true)
    }
  }, [])

  // Every 30 s, but not while the tab is hidden; refresh straight away when it comes back.
  useEffect(() => {
    let timer: ReturnType<typeof setInterval> | undefined
    const start = () => {
      if (timer === undefined) timer = setInterval(() => void load(), REFRESH_MS)
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

  const state: TileState = data ? 'ready' : loaded ? 'error' : 'loading'
  const d = data?.drivers
  const r = data?.rides
  const c = data?.customers
  const v = data?.verification
  const s = data?.safety
  const a = data?.adoption
  const l = data?.loginAlerts

  const sosWaitingTooLong =
    s?.oldestUnacknowledgedSosMinutes != null && s.oldestUnacknowledgedSosMinutes > (s.sosTargetMinutes ?? 5)
  const sosWaiting = s?.oldestUnacknowledgedSosMinutes != null
  const sosMedianOver = s?.sosMedianAckMinutes != null && s.sosMedianAckMinutes > (s.sosTargetMinutes ?? 5)
  const reviewOver = (v?.waitingOverTarget ?? 0) > 0
  const reviewMedianOver = v?.medianWaitHours != null && v.medianWaitHours > (v.targetHours ?? 24)
  const empty =
    !!data && (a?.captainsRegistered ?? 0) === 0 && (c?.total ?? 0) === 0 && (a?.weeklyRides ?? []).every((w) => !w.completed)

  return (
    <section className="dashboard">
      <h1>Dashboard</h1>
      <p className="muted">
        Refreshes every 30 seconds while this tab is open. “Today” is the India calendar day
        {data?.today ? ` (${data.today})` : ''}.
      </p>

      {error && (
        <div role="alert" className="banner banner-bad">
          {error} {data ? 'Showing the last figures that loaded.' : ''}{' '}
          <button type="button" className="link-button" onClick={() => void load()}>
            Try again
          </button>
        </div>
      )}

      {empty && (
        <div role="status" className="panel">
          <strong>No activity yet.</strong> Figures appear here as captains register and riders book their first ride.
        </div>
      )}

      <h2>Right now</h2>
      <div className="tiles">
        <Tile label="Active drivers" state={state} value={d && number.format(d.active ?? 0)} note="Approved captains" to="/drivers" />
        <Tile
          label="Online drivers"
          state={state}
          value={d && number.format(d.online ?? 0)}
          note={`Free to take a ride, position under ${d?.onlineMaxAgeMinutes ?? 10} min old`}
          to="/live-map"
        />
        <Tile label="Active rides" state={state} value={r && number.format(r.active ?? 0)} note="Driver assigned or ride started" to="/live-map" />
        <Tile label="Pending bookings" state={state} value={r && number.format(r.pendingBookings ?? 0)} note="Waiting for a driver" to="/rides" />
        <Tile
          label="Driver verification requests"
          state={state}
          value={v && number.format(v.waiting ?? 0)}
          note={`${number.format(v?.waitingOverTarget ?? 0)} waiting over ${v?.targetHours ?? 24} h`}
          bad={reviewOver}
          to="/driver-verification"
        />
        <Tile
          label="Open safety incidents"
          state={state}
          value={s && number.format(s.unresolved ?? 0)}
          note={`${number.format(s?.needsAcknowledging ?? 0)} to acknowledge · ${number.format(s?.critical ?? 0)} critical`}
          bad={sosWaitingTooLong}
          to="/safety"
        />
        <Tile
          label="System alerts"
          state={state}
          value={l && number.format(l.accountsWithRepeatedFailures ?? 0)}
          note={`Admin sign-in addresses with ${l?.failureThreshold ?? 10}+ failed attempts in ${l?.windowMinutes ?? 10} min. Nothing else is monitored here.`}
          bad={(l?.accountsWithRepeatedFailures ?? 0) > 0}
          flag="Needs a look"
        />
      </div>

      <h2>Today</h2>
      <div className="tiles">
        <Tile label="Completed rides (today)" state={state} value={r && number.format(r.completedToday ?? 0)} to="/rides" />
        <Tile label="Cancelled rides (today)" state={state} value={r && number.format(r.cancelledToday ?? 0)} to="/rides" />
        <Tile
          label="Cash fares collected (today)"
          state={state}
          value={r && rupees.format(Number(r.cashFaresTodayRupees ?? 0))}
          note="Fares riders paid captains in cash. Not Lady Go revenue."
          to="/rides"
        />
        <Tile
          label="Customers"
          state={state}
          value={c && number.format(c.total ?? 0)}
          note={`${number.format(c?.newLast7Days ?? 0)} new in the last 7 days`}
          to="/customers"
        />
      </div>

      <h2>Service targets</h2>
      <div className="tiles">
        <Tile
          label="Driver review wait (median)"
          state={state}
          value={v && hours(v.medianWaitHours)}
          note={`Longest ${hours(v?.longestWaitHours)} · target ${v?.targetHours ?? 24} h`}
          bad={reviewMedianOver || reviewOver}
          to="/driver-verification"
        />
        <Tile
          label="SOS acknowledged in (median)"
          state={state}
          value={s && (sosWaiting && s.sosMedianAckMinutes == null ? 'SOS waiting' : minutes(s.sosMedianAckMinutes, 'no SOS yet'))}
          note={
            <>
              {sosWaiting && s.sosMedianAckMinutes == null ? (
                <>None acknowledged yet · target {s.sosTargetMinutes ?? 5} min</>
              ) : (
                <>
                  Target {s?.sosTargetMinutes ?? 5} min · based on {number.format(s?.sosAckSample ?? 0)} acknowledged SOS in the last 30 days
                </>
              )}
              {sosWaiting && (
                <strong> · the oldest SOS has waited {s.oldestUnacknowledgedSosMinutes} min with nobody acknowledging it</strong>
              )}
            </>
          }
          bad={sosWaitingTooLong || sosMedianOver}
          to="/safety"
        />
      </div>

      <h2>First 60 days: are people using Lady Go?</h2>
      <div className="tiles">
        <Tile label="Captains registered" state={state} value={a && number.format(a.captainsRegistered ?? 0)} to="/drivers" />
        <Tile
          label="Captains who finished every step"
          state={state}
          value={a && number.format(a.captainsCompletedAllSteps ?? 0)}
          note="Vehicle, RC, selfie and declaration all sent"
          to="/driver-verification"
        />
        <Tile label="Captains approved" state={state} value={a && number.format(a.captainsApproved ?? 0)} to="/drivers" />
        <Tile label="Riders who completed a ride" state={state} value={a && number.format(a.ridersWhoCompletedARide ?? 0)} to="/customers" />
      </div>
      {a?.weeklyRides && <WeeklyRides weeks={a.weeklyRides} />}
    </section>
  )
}
