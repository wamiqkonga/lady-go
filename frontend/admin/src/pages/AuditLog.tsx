import { useCallback, useEffect, useId, useState } from 'react'
import { client, unwrap, ApiCallError } from '../lib/client'
import type { components } from '../lib/api-types'
import { ACTION_LABELS, actionLabel, detailPairs, targetLabel } from '../lib/audit'

type LogRow = components['schemas']['AdminAuditLogResponse']

/** True on a phone-width screen; the details list starts collapsed there. */
function useNarrow(): boolean {
  const query = '(max-width: 760px)'
  const [narrow, setNarrow] = useState(() => (typeof window.matchMedia === 'function' ? window.matchMedia(query).matches : false))
  useEffect(() => {
    if (typeof window.matchMedia !== 'function') return
    const list = window.matchMedia(query)
    const onChange = () => setNarrow(list.matches)
    list.addEventListener('change', onChange)
    return () => list.removeEventListener('change', onChange)
  }, [])
  return narrow
}

/** Backend never puts a secret, token, OTP, or full phone number into an audit-log metadata map
 *  (docs/product/admin-web-design.md section 9 - confirmed by reading every write site), and this
 *  renders every value as a plain text child, never dangerouslySetInnerHTML, so a hostile value
 *  in there would render as literal text rather than run. */
function MetadataCell({ metadata, narrow }: { metadata?: Record<string, unknown>; narrow: boolean }) {
  const pairs = detailPairs(metadata)
  if (pairs.length === 0) return <>—</>
  return (
    <details className="audit-details" open={!narrow}>
      <summary>{pairs.length} {pairs.length === 1 ? 'detail' : 'details'}</summary>
      <dl className="audit-kv">
        {pairs.map((pair) => (
          <div key={pair.label}>
            <dt>{pair.label}</dt>
            <dd>{pair.value}</dd>
          </div>
        ))}
      </dl>
    </details>
  )
}

function Actor({ row }: { row: LogRow }) {
  const who = row.actorName ?? row.actorEmailMasked
  return (
    <>
      <strong>{who ?? 'Unknown'}</strong>
      {row.actorRole && <span className="muted"> ({row.actorRole.toLowerCase().replace('_', ' ')})</span>}
    </>
  )
}

export default function AuditLog() {
  const [rows, setRows] = useState<LogRow[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [actor, setActor] = useState('')
  const ids = useId()
  const narrow = useNarrow()
  const [action, setAction] = useState('')
  const [createdAfter, setCreatedAfter] = useState('')
  const [createdBefore, setCreatedBefore] = useState('')

  const load = useCallback(() => {
    setError(null)
    client
      .GET('/api/v1/admin/audit-logs', {
        params: {
          query: {
            // An email goes to the email filter, anything else is taken as the actor's id.
            actorId: actor.trim() && !actor.includes('@') ? actor.trim() : undefined,
            actorEmail: actor.trim().includes('@') ? actor.trim() : undefined,
            action: action.trim() || undefined,
            createdAfter: createdAfter ? new Date(createdAfter).toISOString() : undefined,
            createdBefore: createdBefore ? new Date(createdBefore).toISOString() : undefined,
            pageable: { page: 0, size: 50, sort: ['createdAt,desc'] },
          },
        },
      })
      .then((result) => setRows(unwrap(result).content ?? []))
      .catch((err: unknown) => setError(err instanceof ApiCallError ? err.message : 'Failed to load the audit log.'))
  }, [actor, action, createdAfter, createdBefore])

  // Deliberately mount-only - this screen has an explicit Apply button rather than filter-as-you-type
  // (unlike the Rides/Flags tabs' dropdowns), so refetching on every keystroke here would be a bug,
  // not a feature: it would hammer the backend once per character typed into Actor ID or Action.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(() => load(), [])

  return (
    <section>
      <h1>Audit log</h1>
      <form className="filters" onSubmit={(e) => { e.preventDefault(); load() }}>
        <div className="filter">
          <label htmlFor={`${ids}-actor`}>Actor (email or ID)</label>
          <input id={`${ids}-actor`} type="text" value={actor} onChange={(e) => setActor(e.target.value)} placeholder="name@example.com or UUID" />
        </div>
        <div className="filter">
          <label htmlFor={`${ids}-action`}>Action</label>
          <select id={`${ids}-action`} value={action} onChange={(e) => setAction(e.target.value)}>
            <option value="">Any action</option>
            {Object.entries(ACTION_LABELS).map(([code, label]) => <option key={code} value={code}>{label}</option>)}
          </select>
        </div>
        <div className="filter">
          <label htmlFor={`${ids}-from`}>From</label>
          <input id={`${ids}-from`} type="date" value={createdAfter} onChange={(e) => setCreatedAfter(e.target.value)} />
        </div>
        <div className="filter">
          <label htmlFor={`${ids}-to`}>To</label>
          <input id={`${ids}-to`} type="date" value={createdBefore} onChange={(e) => setCreatedBefore(e.target.value)} />
        </div>
        <button type="submit" className="btn-primary">Apply</button>
      </form>

      {error && <p role="alert">{error}</p>}
      {!error && rows === null && <p className="muted">Loading…</p>}
      {!error && rows !== null && rows.length === 0 && <p className="muted">No entries match these filters.</p>}
      {!error && rows !== null && rows.length > 0 && (
        <div className="table-scroll">
          <table className="list-table stack-table">
            <thead>
              <tr>
                <th>When</th>
                <th>Actor</th>
                <th>Action</th>
                <th>Target</th>
                <th>Details</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => (
                <tr key={row.id}>
                  <td data-label="When">{row.createdAt ? new Date(row.createdAt).toLocaleString() : '—'}</td>
                  <td data-label="Actor"><Actor row={row} /></td>
                  <td data-label="Action">{actionLabel(row.action)}</td>
                  <td data-label="Target">{targetLabel(row.targetType, row.action)} {row.targetId ? <span className="muted">({row.targetId.slice(0, 8)}…)</span> : ''}</td>
                  <td data-label="Details"><MetadataCell metadata={row.metadata} narrow={narrow} /></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}
