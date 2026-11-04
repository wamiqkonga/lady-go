import { useCallback, useEffect, useState } from 'react'
import { ApiCallError, client, expectOk, unwrap } from '../../lib/client'
import ReasonAction from '../../components/ReasonAction'
import type { components } from '../../lib/api-types'
import FareChangeForm from './FareChangeForm'
import FareHistory from './FareHistory'
import { formatRupees } from './money'
import './pricing.css'

type City = components['schemas']['PricingCityResponse']
type View = components['schemas']['PricingRulesViewResponse']
type VehicleType = 'CAB' | 'SUV' | 'AUTO' | 'BIKE'

const TYPE_NAMES: Record<VehicleType, string> = { BIKE: 'Two-wheeler', AUTO: 'Auto', CAB: 'Cab', SUV: 'SUV' }

/** Only two-wheelers can be booked now, so BIKE is always shown; another type appears only once it already has a fare. */
export function visibleTypes(city: City): VehicleType[] {
  const withFare = (city.vehicleTypesWithFare ?? []).filter((t) => t !== 'BIKE')
  return ['BIKE', ...withFare]
}

function VehicleFareCard({ cityId, vehicleType }: { cityId: string; vehicleType: VehicleType }) {
  const [view, setView] = useState<View | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [editing, setEditing] = useState(false)

  const load = useCallback(() => {
    setError(null)
    client
      .GET('/api/v1/admin/pricing-rules', { params: { query: { cityId, vehicleType } } })
      .then((result) => setView(unwrap(result)))
      .catch((err: unknown) => setError(err instanceof ApiCallError ? err.message : 'Failed to load fares.'))
  }, [cityId, vehicleType])

  useEffect(() => {
    setView(null)
    setEditing(false)
    load()
  }, [load])

  if (error) return <p role="alert">{error}</p>
  if (!view) return <p className="muted">Loading…</p>

  const { current, scheduled } = view
  const name = TYPE_NAMES[vehicleType]

  return (
    <section className={`fare-card${current ? '' : ' no-fare'}`} aria-label={`${name} fare`}>
      <h2>{name}</h2>
      {current ? (
        <>
          <p className="muted">In force since {current.effectiveFrom ? new Date(current.effectiveFrom).toLocaleString() : '-'}</p>
          <dl className="fare-facts">
            <div><dt>Base fare</dt><dd>{formatRupees(current.baseFare)}</dd></div>
            <div><dt>Per km</dt><dd>{formatRupees(current.perKm)}</dd></div>
            <div><dt>Per minute</dt><dd>{formatRupees(current.perMinute)}</dd></div>
            <div><dt>Minimum fare</dt><dd>{formatRupees(current.minimumFare)}</dd></div>
            <div><dt>Cancellation fee</dt><dd>{formatRupees(current.cancellationFee)}</dd></div>
            <div><dt>Service fee</dt><dd>{current.serviceFeePct}% + {formatRupees(current.serviceFeeFlat)}</dd></div>
          </dl>
        </>
      ) : (
        !scheduled && <p className="no-fare-text" role="alert">No fare set: riders cannot book</p>
      )}

      {scheduled && (
        <div className="fare-banner" role="status">
          <strong>Scheduled change</strong> starts {scheduled.effectiveFrom ? new Date(scheduled.effectiveFrom).toLocaleString() : '-'}:
          base {formatRupees(scheduled.baseFare)}, per km {formatRupees(scheduled.perKm)}, per minute {formatRupees(scheduled.perMinute)}, minimum {formatRupees(scheduled.minimumFare)}.
          {scheduled.cancellable ? (
            <ReasonAction
              label="Cancel scheduled change"
              description="The fare now in force stays as it is."
              perform={(reason) =>
                client
                  .POST('/api/v1/admin/pricing-rules/{id}/cancel', { params: { path: { id: scheduled.id! } }, body: { reason } })
                  .then(expectOk)
              }
              onDone={load}
            />
          ) : (
            <p className="muted">A booking already uses this fare, so it cannot be cancelled.</p>
          )}
        </div>
      )}

      {!scheduled && !editing && (
        <button type="button" onClick={() => setEditing(true)}>
          {current ? 'Change fare' : 'Set the first fare'}
        </button>
      )}
      {scheduled && <p className="muted">To change the fare again, cancel the scheduled change first.</p>}
      {editing && (
        <FareChangeForm
          cityId={cityId}
          vehicleType={vehicleType}
          view={view}
          onClose={() => setEditing(false)}
          onSaved={() => { setEditing(false); load() }}
        />
      )}

      <h3>History</h3>
      <FareHistory rules={view.history ?? []} />
    </section>
  )
}

export default function FaresTab() {
  const [cities, setCities] = useState<City[] | null>(null)
  const [cityId, setCityId] = useState<string>('')
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    client
      .GET('/api/v1/admin/pricing-rules/cities')
      .then((result) => {
        const list = unwrap(result)
        setCities(list)
        setCityId((prev) => prev || list[0]?.id || '')
      })
      .catch((err: unknown) => setError(err instanceof ApiCallError ? err.message : 'Failed to load cities.'))
  }, [])

  if (error) return <p role="alert">{error}</p>
  if (cities === null) return <p className="muted">Loading…</p>
  if (cities.length === 0) return <p role="alert" className="no-fare-text">No city exists yet, so no fare can be set.</p>

  const city = cities.find((c) => c.id === cityId) ?? cities[0]

  return (
    <div>
      {cities.length > 1 ? (
        <label>
          City
          <select value={city.id} onChange={(e) => setCityId(e.target.value)}>
            {cities.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
          </select>
        </label>
      ) : (
        <p><strong>{city.name}</strong></p>
      )}
      {!city.hasServiceArea && (
        <p role="alert" className="no-fare-text">This city has no service area set, so riders cannot book here.</p>
      )}
      {visibleTypes(city).map((t) => (
        <VehicleFareCard key={`${city.id}-${t}`} cityId={city.id!} vehicleType={t} />
      ))}
    </div>
  )
}
