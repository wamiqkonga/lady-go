import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { Link, useParams } from 'react-router-dom'
import { client, unwrap, ApiCallError } from '../lib/client'
import {
  ageLabel, OUTCOMES, STATUS_LABELS, TYPE_LABELS, type SafetyDetail, type SafetyOutcome,
} from '../lib/safety'

/** One incident: who, where, the append-only timeline, and the three actions. Resolving needs an outcome. */
export default function SafetyIncident() {
  const { incidentId } = useParams<{ incidentId: string }>()
  const [incident, setIncident] = useState<SafetyDetail | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [note, setNote] = useState('')
  const [outcome, setOutcome] = useState<SafetyOutcome | ''>('')
  const [resolveNote, setResolveNote] = useState('')

  const load = useCallback(async () => {
    if (!incidentId) return
    try {
      const result = await client.GET('/api/v1/admin/safety/incidents/{id}', { params: { path: { id: incidentId } } })
      setIncident(unwrap(result))
      setLoadError(null)
    } catch (err) {
      setLoadError(err instanceof ApiCallError ? err.message : 'Failed to load this incident.')
    }
  }, [incidentId])

  useEffect(() => {
    const first = window.setTimeout(() => void load(), 0)
    return () => window.clearTimeout(first)
  }, [load])

  async function run(failure: string, action: () => Promise<SafetyDetail>) {
    setBusy(true)
    setActionError(null)
    try {
      setIncident(await action())
    } catch (err) {
      setActionError(err instanceof ApiCallError ? err.message : failure)
    } finally {
      setBusy(false)
    }
  }

  function acknowledge() {
    if (!incidentId) return
    return run('Could not acknowledge this incident.', async () =>
      unwrap(await client.POST('/api/v1/admin/safety/incidents/{id}/acknowledge', { params: { path: { id: incidentId } } })))
  }

  function addNote(e: FormEvent) {
    e.preventDefault()
    if (!incidentId || !note.trim()) return
    return run('Could not add the note.', async () => {
      const updated = unwrap(await client.POST('/api/v1/admin/safety/incidents/{id}/notes', {
        params: { path: { id: incidentId } }, body: { note: note.trim() },
      }))
      setNote('')
      return updated
    })
  }

  function resolve(e: FormEvent) {
    e.preventDefault()
    if (!incidentId || !outcome) return
    return run('Could not resolve this incident.', async () => {
      const updated = unwrap(await client.POST('/api/v1/admin/safety/incidents/{id}/resolve', {
        params: { path: { id: incidentId } }, body: { outcome, note: resolveNote.trim() || undefined },
      }))
      setOutcome('')
      setResolveNote('')
      return updated
    })
  }

  if (loadError) return <p role="alert">{loadError}</p>
  if (!incident) return <p className="muted">Loading…</p>

  const resolved = incident.status === 'RESOLVED'
  const isSos = incident.type === 'SOS'

  return (
    <section>
      <p><Link to="/safety">← Safety queue</Link></p>
      <h1>{TYPE_LABELS[incident.type ?? ''] ?? incident.type}</h1>
      {isSos && !resolved && (
        <p role="alert" className="banner-danger">
          If someone is in danger, call 112 first. Then acknowledge and record what was done.
        </p>
      )}
      <p className="muted">
        <span className={`sev sev-${(incident.severity ?? 'LOW').toLowerCase()}`}>{incident.severity}</span>{' '}
        · {STATUS_LABELS[incident.status ?? ''] ?? incident.status} · raised {ageLabel(incident.createdAt)}
      </p>
      {actionError && <p role="alert">{actionError}</p>}

      <div className="panel">
        <h2>Details</h2>
        <p>
          Reported by <strong>{incident.reporter?.name ?? 'unknown'}</strong>
          {incident.reporter?.role ? ` (${incident.reporter.role.toLowerCase()})` : ''}
          {incident.reporter?.phoneMasked ? ` · ${incident.reporter.phoneMasked}` : ''}
        </p>
        {incident.notes && <p>“{incident.notes}”</p>}
        {incident.location ? (
          <p>
            Last position {incident.location.lat?.toFixed(5)}, {incident.location.lng?.toFixed(5)} ·{' '}
            <a
              href={`https://www.openstreetmap.org/?mlat=${incident.location.lat}&mlon=${incident.location.lng}#map=16/${incident.location.lat}/${incident.location.lng}`}
              target="_blank" rel="noopener noreferrer"
            >
              open in map
            </a>
          </p>
        ) : (
          <p className="muted">No position was recorded.</p>
        )}
        {incident.bookingId && <p><Link to={`/rides/${incident.bookingId}`}>View the ride</Link></p>}
        {incident.legalHold && <p className="muted">Legal hold: this ride’s data is kept.</p>}
      </div>

      <div className="panel">
        <h2>Timeline</h2>
        <ol className="timeline">
          {(incident.timeline ?? []).map((event) => (
            <li key={event.id}>
              <strong>{event.eventType}</strong>
              {event.outcome ? ` · ${OUTCOMES.find((o) => o.value === event.outcome)?.label ?? event.outcome}` : ''}
              <span className="muted">
                {' '}· {event.actorName ?? 'system'}{event.actorRole ? ` (${event.actorRole.toLowerCase()})` : ''} ·{' '}
                {event.createdAt ? new Date(event.createdAt).toLocaleString() : ''}
              </span>
              {event.notes && <div>{event.notes}</div>}
            </li>
          ))}
        </ol>
      </div>

      {!resolved && (
        <div className="panel">
          <h2>Actions</h2>
          {incident.status === 'OPEN' && (
            <div className="row-actions">
              <button type="button" onClick={acknowledge} disabled={busy}>Acknowledge</button>
            </div>
          )}
          <form onSubmit={addNote} className="form">
            <label htmlFor="safety-note">Add a note</label>
            <textarea id="safety-note" rows={3} maxLength={2000} value={note} onChange={(e) => setNote(e.target.value)} />
            <button type="submit" disabled={busy || !note.trim()}>Add note</button>
          </form>
          <form onSubmit={resolve} className="form">
            <label htmlFor="safety-outcome">Resolve with outcome</label>
            <select id="safety-outcome" value={outcome} onChange={(e) => setOutcome(e.target.value as SafetyOutcome | '')}>
              <option value="">Choose what happened…</option>
              {OUTCOMES.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
            </select>
            <label htmlFor="safety-resolve-note">Note (optional)</label>
            <textarea id="safety-resolve-note" rows={2} maxLength={2000} value={resolveNote} onChange={(e) => setResolveNote(e.target.value)} />
            <button type="submit" disabled={busy || !outcome}>Resolve incident</button>
          </form>
        </div>
      )}
    </section>
  )
}
