import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { client, unwrap, ApiCallError } from '../lib/client'
import { ageLabel, STATUS_LABELS, TYPE_LABELS, type SafetySummary } from '../lib/safety'

const REFRESH_MS = 15_000

/** The safety queue: unresolved incidents, most severe first then oldest first (the server sorts). Refreshes itself. */
export default function SafetyQueue() {
  const [view, setView] = useState<'open' | 'resolved'>('open')
  const [rows, setRows] = useState<SafetySummary[] | null>(null)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(async () => {
    try {
      const result = await client.GET('/api/v1/admin/safety/incidents', {
        params: { query: { status: view === 'resolved' ? 'RESOLVED' : undefined, pageable: { page: 0, size: 50 } } },
      })
      setRows(unwrap(result).content ?? [])
      setError(null)
    } catch (err) {
      setError(err instanceof ApiCallError ? err.message : 'Failed to load the safety queue.')
    }
  }, [view])

  useEffect(() => {
    // Defer the first load out of the effect body so no state is set synchronously inside it.
    const first = window.setTimeout(() => void load(), 0)
    if (view !== 'open') return () => window.clearTimeout(first)
    const timer = window.setInterval(() => {
      if (document.visibilityState !== 'hidden') void load()
    }, REFRESH_MS)
    return () => {
      window.clearTimeout(first)
      window.clearInterval(timer)
    }
  }, [load, view])

  const hasOpenSos = (rows ?? []).some((r) => r.type === 'SOS' && r.status !== 'RESOLVED')

  return (
    <section>
      <h1>Safety</h1>
      {hasOpenSos && (
        <p role="alert" className="banner-danger">
          An SOS is open. If someone is in danger, call 112 first, then acknowledge the incident here.
        </p>
      )}
      <div className="row-actions">
        <button type="button" aria-pressed={view === 'open'} onClick={() => { setRows(null); setView('open') }}>
          Open
        </button>
        <button type="button" aria-pressed={view === 'resolved'} onClick={() => { setRows(null); setView('resolved') }}>
          Resolved
        </button>
      </div>
      {error && <p role="alert">{error}</p>}
      {rows === null && !error && <p className="muted">Loading…</p>}
      {rows !== null && rows.length === 0 && (
        <p className="muted">{view === 'open' ? 'No open safety incidents.' : 'No resolved incidents yet.'}</p>
      )}
      {rows !== null && rows.length > 0 && (
        <div className="table-scroll">
          <table className="list-table">
            <thead>
              <tr>
                <th>Severity</th>
                <th>Type</th>
                <th>Reported by</th>
                <th>Status</th>
                <th>Raised</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => (
                <tr key={row.id}>
                  <td>
                    <span className={`sev sev-${(row.severity ?? 'LOW').toLowerCase()}`}>{row.severity}</span>
                  </td>
                  <td>
                    <Link to={`/safety/${row.id}`}>{TYPE_LABELS[row.type ?? ''] ?? row.type}</Link>
                  </td>
                  <td>
                    {row.reporter?.name ?? '—'} <span className="muted">{row.reporter?.phoneMasked ?? ''}</span>
                  </td>
                  <td>{STATUS_LABELS[row.status ?? ''] ?? row.status}</td>
                  <td>{ageLabel(row.createdAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}
