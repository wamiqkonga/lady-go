import { useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { ApiCallError, client, unwrap } from '../../lib/client'
import { useStepUp } from '../../lib/stepUpContext'
import type { components } from '../../lib/api-types'
import { formatRupees, paiseToRupeesText, parsePercent, rupeesToPaise } from './money'

type View = components['schemas']['PricingRulesViewResponse']
type Rule = components['schemas']['PricingRuleResponse']
type Preview = components['schemas']['PricingPreviewResponse']

type MoneyKey = 'baseFare' | 'perKm' | 'perMinute' | 'minimumFare' | 'cancellationFee' | 'serviceFeeFlat'
const MONEY_FIELDS: { key: MoneyKey; label: string }[] = [
  { key: 'baseFare', label: 'Base fare (rupees)' },
  { key: 'perKm', label: 'Per kilometre (rupees)' },
  { key: 'perMinute', label: 'Per minute (rupees)' },
  { key: 'minimumFare', label: 'Minimum fare (rupees)' },
  { key: 'cancellationFee', label: 'Cancellation fee (rupees)' },
  { key: 'serviceFeeFlat', label: 'Service fee, flat (rupees)' },
]
const FIELD_NAMES: Record<string, string> = {
  baseFare: 'Base fare', perKm: 'Per kilometre', perMinute: 'Per minute', minimumFare: 'Minimum fare',
  cancellationFee: 'Cancellation fee', serviceFeeFlat: 'Service fee (flat)', serviceFeePct: 'Service fee (percent)',
}

type Props = {
  cityId: string
  vehicleType: 'CAB' | 'SUV' | 'AUTO' | 'BIKE'
  view: View
  onClose: () => void
  onSaved: () => void
}

function initial(current: Rule | undefined): Record<MoneyKey | 'serviceFeePct', string> {
  const money = (n: number | undefined) => (current && n !== undefined ? paiseToRupeesText(n) : '')
  return {
    baseFare: money(current?.baseFare), perKm: money(current?.perKm), perMinute: money(current?.perMinute),
    minimumFare: money(current?.minimumFare), cancellationFee: money(current?.cancellationFee),
    serviceFeeFlat: money(current?.serviceFeeFlat), serviceFeePct: current ? String(current.serviceFeePct ?? 0) : '',
  }
}

function formatSample(preview: Preview['samples'] extends (infer S)[] | undefined ? S : never): string {
  return `${((preview.distanceMeters ?? 0) / 1000).toFixed(0)} km / ${Math.round((preview.durationSeconds ?? 0) / 60)} min`
}

export default function FareChangeForm({ cityId, vehicleType, view, onClose, onSaved }: Props) {
  const { runWithStepUp } = useStepUp()
  const current = view.current
  const [values, setValues] = useState(() => initial(current))
  const [when, setWhen] = useState<'now' | 'later'>('now')
  const [laterAt, setLaterAt] = useState('')
  const [confirmLarge, setConfirmLarge] = useState(false)
  const [reviewing, setReviewing] = useState(false)
  const [reason, setReason] = useState('')
  const [preview, setPreview] = useState<Preview | null>(null)
  const [previewError, setPreviewError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [needsSetup, setNeedsSetup] = useState(false)

  const parsed = useMemo(() => {
    const errors: Partial<Record<MoneyKey | 'serviceFeePct', string>> = {}
    const money: Partial<Record<MoneyKey, number>> = {}
    for (const f of MONEY_FIELDS) {
      const r = rupeesToPaise(values[f.key])
      if (r.ok) money[f.key] = r.paise
      else errors[f.key] = r.error
    }
    const pct = parsePercent(values.serviceFeePct)
    if (!pct.ok) errors.serviceFeePct = pct.error
    const complete = Object.keys(errors).length === 0 && pct.ok
    return { errors, money, pct: pct.ok ? pct.value : undefined, complete }
  }, [values])

  const laterIso = useMemo(() => {
    if (when !== 'later' || !laterAt) return undefined
    const d = new Date(laterAt)
    return Number.isNaN(d.getTime()) ? undefined : d.toISOString()
  }, [when, laterAt])
  const laterProblem =
    when === 'later' && (!laterIso || new Date(laterIso).getTime() <= Date.now()) ? 'Choose a time in the future.' : null

  const body = useMemo(() => {
    if (!parsed.complete || parsed.pct === undefined) return null
    const m = parsed.money as Record<MoneyKey, number>
    return { cityId, vehicleType, ...m, serviceFeePct: parsed.pct }
  }, [parsed, cityId, vehicleType])

  // Live sample fares from the real calculator, debounced; nothing is saved by a preview.
  useEffect(() => {
    if (!body) {
      setPreview(null)
      setPreviewError(null)
      return
    }
    let cancelled = false
    const timer = setTimeout(() => {
      client
        .POST('/api/v1/admin/pricing-rules/preview', { body })
        .then((result) => unwrap(result))
        .then((data) => {
          if (cancelled) return
          setPreview(data)
          setPreviewError(null)
        })
        .catch((err: unknown) => {
          if (cancelled) return
          setPreview(null)
          setPreviewError(err instanceof ApiCallError ? err.message : 'Could not calculate sample fares.')
        })
    }, 300)
    return () => {
      cancelled = true
      clearTimeout(timer)
    }
  }, [body])

  const largeChanges = preview?.largeChanges ?? []
  const needsConfirm = largeChanges.length > 0
  const canReview = !!body && !previewError && !laterProblem && (!needsConfirm || confirmLarge)

  async function save() {
    if (!body || !reason.trim()) return
    setBusy(true)
    setError(null)
    setNeedsSetup(false)
    try {
      await runWithStepUp(async () => {
        const result = await client.POST('/api/v1/admin/pricing-rules', {
          body: {
            ...body,
            effectiveFrom: when === 'later' ? laterIso : undefined,
            expectedLatestRuleId: view.latestRuleId ?? undefined,
            reason: reason.trim(),
            confirmLargeChange: needsConfirm && confirmLarge,
          },
        })
        return unwrap(result)
      })
      onSaved()
    } catch (err) {
      if (err instanceof ApiCallError && err.code === 'MFA_ENROLMENT_REQUIRED') setNeedsSetup(true)
      setError(err instanceof ApiCallError ? err.message : 'That did not work. Try again.')
    } finally {
      setBusy(false)
    }
  }

  const oldText = (key: MoneyKey) => (current ? formatRupees(current[key]) : '-')

  return (
    <div className="fare-form-wrap" role="group" aria-label={current ? 'Change fare' : 'Set the first fare'}>
      {!reviewing ? (
        <form className="fare-form" onSubmit={(e) => { e.preventDefault(); if (canReview) setReviewing(true) }}>
          {MONEY_FIELDS.map((f) => (
            <label key={f.key}>
              {f.label}
              <input
                type="text"
                inputMode="decimal"
                value={values[f.key]}
                onChange={(e) => setValues({ ...values, [f.key]: e.target.value })}
                aria-invalid={!!parsed.errors[f.key]}
              />
              {values[f.key] !== '' && parsed.errors[f.key] && <p className="field-error">{parsed.errors[f.key]}</p>}
            </label>
          ))}
          <label>
            Service fee (percent)
            <input
              type="text"
              inputMode="decimal"
              value={values.serviceFeePct}
              onChange={(e) => setValues({ ...values, serviceFeePct: e.target.value })}
              aria-invalid={!!parsed.errors.serviceFeePct}
            />
            {values.serviceFeePct !== '' && parsed.errors.serviceFeePct && <p className="field-error">{parsed.errors.serviceFeePct}</p>}
          </label>

          <fieldset>
            <legend>When should it start?</legend>
            <label><input type="radio" name="when" checked={when === 'now'} onChange={() => setWhen('now')} /> Now</label>
            <label><input type="radio" name="when" checked={when === 'later'} onChange={() => setWhen('later')} /> At a later time</label>
            {when === 'later' && (
              <label>
                Start time
                <input type="datetime-local" value={laterAt} onChange={(e) => setLaterAt(e.target.value)} />
              </label>
            )}
            {laterProblem && <p className="field-error">{laterProblem}</p>}
          </fieldset>

          {previewError && <p role="alert" className="field-error">{previewError}</p>}

          {preview && (
            <div className="fare-samples">
              <h3>What a rider would pay</h3>
              <table aria-label="Sample fares">
                <thead>
                  <tr><th>Trip</th><th>Current</th><th>New</th></tr>
                </thead>
                <tbody>
                  {(preview.samples ?? []).map((s) => (
                    <tr key={`${s.distanceMeters}-${s.durationSeconds}`}>
                      <td>{formatSample(s)}</td>
                      <td>{s.current ? formatRupees(s.current.totalFare) : '-'}</td>
                      <td>{formatRupees(s.proposed?.totalFare)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}

          {needsConfirm && (
            <div className="fare-warning" role="alert">
              <p>Large change: more than {view.largeChangePct} percent. Check these numbers.</p>
              <ul>
                {largeChanges.map((c) => (
                  <li key={c.field}>
                    {FIELD_NAMES[c.field ?? ''] ?? c.field}: {c.field === 'serviceFeePct' ? `${c.oldValue}% to ${c.newValue}%` : `${formatRupees(Number(c.oldValue))} to ${formatRupees(Number(c.newValue))}`}
                  </li>
                ))}
              </ul>
              <label>
                <input type="checkbox" checked={confirmLarge} onChange={(e) => setConfirmLarge(e.target.checked)} /> I confirm this large change
              </label>
            </div>
          )}

          <div className="row-actions">
            <button type="submit" disabled={!canReview}>Review change</button>
            <button type="button" onClick={onClose}>Cancel</button>
          </div>
        </form>
      ) : (
        <div className="fare-form">
          <h3>Confirm the change</h3>
          <table className="fare-summary" aria-label="Change summary">
            <thead><tr><th>Item</th><th>Old</th><th>New</th></tr></thead>
            <tbody>
              {MONEY_FIELDS.map((f) => (
                <tr key={f.key}>
                  <td>{FIELD_NAMES[f.key]}</td>
                  <td>{oldText(f.key)}</td>
                  <td>{formatRupees(parsed.money[f.key])}</td>
                </tr>
              ))}
              <tr>
                <td>Service fee (percent)</td>
                <td>{current ? `${current.serviceFeePct}%` : '-'}</td>
                <td>{parsed.pct}%</td>
              </tr>
            </tbody>
          </table>
          <p>Starts: <strong>{when === 'now' ? 'now' : new Date(laterIso ?? '').toLocaleString()}</strong></p>
          <label>
            Reason (required)
            <input type="text" maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} />
          </label>
          <div className="row-actions">
            <button type="button" onClick={save} disabled={busy || !reason.trim()}>Save fare</button>
            <button type="button" onClick={() => setReviewing(false)} disabled={busy}>Back</button>
          </div>
          {error && <p role="alert">{error}</p>}
          {needsSetup && <Link to="/security">Go to Security to set up an authenticator</Link>}
        </div>
      )}
    </div>
  )
}
