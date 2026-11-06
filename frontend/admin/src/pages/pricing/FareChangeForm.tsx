import { useEffect, useId, useMemo, useState, type ReactNode } from 'react'
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

/** The value format a datetime-local input takes (local time, no seconds). */
function localInputValue(epochMs: number): string {
  const d = new Date(epochMs)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`
}

export default function FareChangeForm({ cityId, vehicleType, view, onClose, onSaved }: Props) {
  const { runWithStepUp } = useStepUp()
  const current = view.current
  const [values, setValues] = useState(() => initial(current))
  const [when, setWhen] = useState<'now' | 'later'>('now')
  const [laterAt, setLaterAt] = useState('')
  // The tick is tied to the exact set of large changes it was given for: editing another field so that a different
  // field becomes a large change must ask again.
  const [confirmedFor, setConfirmedFor] = useState<string | null>(null)
  const [reviewing, setReviewing] = useState(false)
  const [reason, setReason] = useState('')
  const [preview, setPreview] = useState<Preview | null>(null)
  const [previewError, setPreviewError] = useState<string | null>(null)
  const [previewing, setPreviewing] = useState(false)
  const uid = useId()
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
  const maxDays = view.limits?.scheduleMaxDays ?? 90
  const laterProblem =
    when !== 'later'
      ? null
      : !laterIso || new Date(laterIso).getTime() <= Date.now()
        ? 'Choose a time in the future.'
        : new Date(laterIso).getTime() > Date.now() + maxDays * 86_400_000
          ? `Choose a time within the next ${maxDays} days.`
          : null

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
      setPreviewing(false)
      return
    }
    let cancelled = false
    setPreview(null)
    setPreviewError(null)
    setPreviewing(true)
    const timer = setTimeout(() => {
      client
        .POST('/api/v1/admin/pricing-rules/preview', { body })
        .then((result) => unwrap(result))
        .then((data) => {
          if (cancelled) return
          setPreview(data)
          setPreviewError(null)
          setPreviewing(false)
        })
        .catch((err: unknown) => {
          if (cancelled) return
          setPreview(null)
          setPreviewing(false)
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
  const largeSignature = JSON.stringify(largeChanges)
  const confirmLarge = confirmedFor === largeSignature
  // The server's own check of the numbers (for example an all-zero fare) must pass before the review step opens.
  const canReview = !!body && !!preview && !previewing && !previewError && !laterProblem && (!needsConfirm || confirmLarge)

  async function save() {
    if (!body || !reason.trim() || previewError) return
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

  function setField(key: MoneyKey | 'serviceFeePct', value: string) {
    setValues((v) => ({ ...v, [key]: value }))
    setError(null)
    setNeedsSetup(false)
    setReason('')
  }

  function goBack() {
    setReviewing(false)
    setError(null)
    setNeedsSetup(false)
    setReason('')
  }

  function fieldFor(key: MoneyKey | 'serviceFeePct', label: string): ReactNode {
    const id = `${uid}-${key}`
    const errorId = `${id}-error`
    const message = values[key] !== '' ? parsed.errors[key] : undefined
    return (
      <div className="field" key={key}>
        <label htmlFor={id}>{label}</label>
        <input
          id={id}
          type="text"
          inputMode="decimal"
          value={values[key]}
          onChange={(e) => setField(key, e.target.value)}
          aria-invalid={message ? true : undefined}
          aria-describedby={message ? errorId : undefined}
        />
        {message && <p id={errorId} className="field-error">{message}</p>}
      </div>
    )
  }

  const oldText = (key: MoneyKey) => (current ? formatRupees(current[key]) : '-')

  return (
    <div className="fare-form-wrap" role="group" aria-label={current ? 'Change fare' : 'Set the first fare'}>
      {!reviewing ? (
        <form className="fare-form" onSubmit={(e) => { e.preventDefault(); if (canReview) setReviewing(true) }}>
          {MONEY_FIELDS.map((f) => fieldFor(f.key, f.label))}
          {fieldFor('serviceFeePct', 'Service fee (percent)')}

          <fieldset>
            <legend>When should it start?</legend>
            <label><input type="radio" name={`${uid}-when`} value="now" checked={when === 'now'} onChange={() => setWhen('now')} /> Now</label>
            <label><input type="radio" name={`${uid}-when`} value="later" checked={when === 'later'} onChange={() => setWhen('later')} /> At a later time</label>
            {when === 'later' && (
              <div className="field">
                <label htmlFor={`${uid}-start`}>Start time</label>
                <input
                  id={`${uid}-start`}
                  type="datetime-local"
                  value={laterAt}
                  max={localInputValue(Date.now() + maxDays * 86_400_000)}
                  onChange={(e) => setLaterAt(e.target.value)}
                  aria-describedby={`${uid}-start-help${laterProblem ? ` ${uid}-start-error` : ''}`}
                  aria-invalid={laterProblem ? true : undefined}
                />
              </div>
            )}
            {when === 'later' && <p id={`${uid}-start-help`} className="muted">A fare can be scheduled at most {maxDays} days ahead.</p>}
            {laterProblem && <p id={`${uid}-start-error`} className="field-error">{laterProblem}</p>}
          </fieldset>

          {previewError && <p role="alert" className="field-error">{previewError}</p>}
          {previewing && !previewError && <p className="muted" role="status">Checking these numbers…</p>}

          {preview && (
            <div className="fare-samples">
              <h3>What a rider would pay</h3>
              <table aria-label="Sample fares">
                <thead>
                  <tr><th>Trip</th>{current && <th>Current</th>}<th>New</th></tr>
                </thead>
                <tbody>
                  {(preview.samples ?? []).map((s) => (
                    <tr key={`${s.distanceMeters}-${s.durationSeconds}`}>
                      <td>{formatSample(s)}</td>
                      {current && <td>{s.current ? formatRupees(s.current.totalFare) : '-'}</td>}
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
                <input type="checkbox" checked={confirmLarge} onChange={(e) => setConfirmedFor(e.target.checked ? largeSignature : null)} /> I confirm this large change
              </label>
            </div>
          )}

          <div className="row-actions">
            <button type="submit" className="btn-primary" disabled={!canReview}>Review change</button>
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
          {previewError && <p role="alert" className="field-error">{previewError} Go back and change the numbers.</p>}
          <div className="field">
            <label htmlFor={`${uid}-reason`}>Reason (required)</label>
            <input id={`${uid}-reason`} type="text" maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} />
          </div>
          <div className="row-actions">
            <button type="button" className="btn-primary" onClick={save} disabled={busy || !reason.trim() || !!previewError}>Save fare</button>
            <button type="button" onClick={goBack} disabled={busy}>Back</button>
          </div>
          {error && <p role="alert">{error}</p>}
          {needsSetup && <Link to="/security">Go to Security to set up an authenticator</Link>}
        </div>
      )}
    </div>
  )
}
