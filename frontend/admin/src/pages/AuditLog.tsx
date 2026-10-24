import { useCallback, useEffect, useState } from 'react'
import { client, unwrap, ApiCallError } from '../lib/client'
import type { components } from '../lib/api-types'

type LogRow = components['schemas']['AdminAuditLogResponse']

/** Backend never puts a secret, token, OTP, or full phone number into an audit-log metadata map
 *  (docs/product/admin-web-design.md section 9 - confirmed by reading every write site), and this
 *  renders the whole thing as a plain text child, never dangerouslySetInnerHTML, so a hostile value
 *  in there would render as literal text rather than run. */
function MetadataCell({ metadata }: { metadata?: Record<string, unknown> }) {
  if (!metadata || Object.keys(metadata).length === 0) return <>—</>
  return <>{JSON.stringify(metadata)}</>
}

export default function AuditLog() {
  const [rows, setRows] = useState<LogRow[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [actorId, setActorId] = useState('')
  const [action, setAction] = useState('')
  const [createdAfter, setCreatedAfter] = useState('')
  const [createdBefore, setCreatedBefore] = useState('')

  const load = useCallback(() => {
    setError(null)
    client
      .GET('/api/v1/admin/audit-logs', {
        params: {
          query: {
            actorId: actorId.trim() || undefined,
            action: action.trim() || undefined,
            createdAfter: createdAfter ? new Date(createdAfter).toISOString() : undefined,
            createdBefore: createdBefore ? new Date(createdBefore).toISOString() : undefined,
            pageable: { page: 0, size: 50, sort: ['createdAt,desc'] },
          },
        },
      })
      .then((result) => setRows(unwrap(result).content ?? []))
      .catch((err: unknown) => setError(err instanceof ApiCallError ? err.message : 'Failed to load the audit log.'))
  }, [actorId, action, createdAfter, createdBefore])

  // Deliberately mount-only - this screen has an explicit Apply button rather than filter-as-you-type
  // (unlike the Rides/Flags tabs' dropdowns), so refetching on every keystroke here would be a bug,
  // not a feature: it would hammer the backend once per character typed into Actor ID or Action.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(() => load(), [])

  return (
    <section>
      <h1>Audit log</h1>
      <div className="filters">
        <label>
          Actor ID
          <input type="text" value={actorId} onChange={(e) => setActorId(e.target.value)} placeholder="UUID" />
        </label>
        <label>
          Action
          <input type="text" value={action} onChange={(e) => setAction(e.target.value)} placeholder="e.g. CONFIGURATION_CHANGED" />
        </label>
        <label>
          From
          <input type="date" value={createdAfter} onChange={(e) => setCreatedAfter(e.target.value)} />
        </label>
        <label>
          To
          <input type="date" value={createdBefore} onChange={(e) => setCreatedBefore(e.target.value)} />
        </label>
        <button type="button" onClick={load}>Apply</button>
      </div>

      {error && <p role="alert">{error}</p>}
      {!error && rows === null && <p className="muted">Loading…</p>}
      {!error && rows !== null && rows.length === 0 && <p className="muted">No entries match these filters.</p>}
      {!error && rows !== null && rows.length > 0 && (
        <table className="list-table">
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
                <td>{row.createdAt ? new Date(row.createdAt).toLocaleString() : '—'}</td>
                <td>{row.actorRole ?? '—'}</td>
                <td>{row.action}</td>
                <td>{row.targetType ?? '—'} {row.targetId ? `(${row.targetId.slice(0, 8)}…)` : ''}</td>
                <td><MetadataCell metadata={row.metadata} /></td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  )
}
