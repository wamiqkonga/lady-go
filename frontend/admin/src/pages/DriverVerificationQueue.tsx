import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import VerificationTabs from '../components/VerificationTabs'
import { client, unwrap, ApiCallError } from '../lib/client'
import type { components } from '../lib/api-types'

type QueueRow = components['schemas']['DriverVerificationQueueItemResponse']
type Status = NonNullable<QueueRow['verificationStatus']>

const STATUS_OPTIONS: { value: Status; label: string }[] = [
  { value: 'PENDING_REVIEW', label: 'Waiting for review' },
  { value: 'REJECTED', label: 'Rejected' },
  { value: 'APPROVED', label: 'Approved' },
  { value: 'SUSPENDED', label: 'Suspended' },
  { value: 'NOT_SUBMITTED', label: 'Not submitted' },
]
const PAGE_SIZE = 20

/** "5h 20m", "2d 3h" - how long a captain has waited, in the two biggest units that matter. */
export function formatWaiting(totalSeconds: number): string {
  const minutes = Math.floor(totalSeconds / 60)
  if (minutes < 1) return 'under 1m'
  if (minutes < 60) return `${minutes}m`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours}h ${minutes % 60}m`
  return `${Math.floor(hours / 24)}d ${hours % 24}h`
}

/** "1 approved · 2 pending" - only the counts that are not zero, in the order a reviewer cares about. */
export function summariseItems(items: NonNullable<QueueRow['requiredItems']>): string {
  const parts = [
    items.pending ? `${items.pending} pending` : '',
    items.rejected ? `${items.rejected} rejected` : '',
    items.missing ? `${items.missing} missing` : '',
    items.approved ? `${items.approved} approved` : '',
  ].filter(Boolean)
  return parts.join(' · ') || '—'
}

export default function DriverVerificationQueue() {
  const [rows, setRows] = useState<QueueRow[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [draft, setDraft] = useState({ q: '', status: 'PENDING_REVIEW', overdueOnly: false, readyOnly: false })
  const [applied, setApplied] = useState(draft)
  const [page, setPage] = useState(0)
  const [totalPages, setTotalPages] = useState(0)
  const [totalElements, setTotalElements] = useState(0)

  const load = useCallback(() => {
    setError(null)
    setRows(null)
    client
      .GET('/api/v1/admin/driver-verification/queue', {
        params: {
          query: {
            q: applied.q || undefined,
            status: applied.status as Status,
            overdueOnly: applied.overdueOnly || undefined,
            readyOnly: applied.readyOnly || undefined,
            pageable: { page, size: PAGE_SIZE },
          },
        },
      })
      .then((result) => {
        const data = unwrap(result)
        setRows(data.content ?? [])
        setTotalPages(data.totalPages ?? 0)
        setTotalElements(data.totalElements ?? 0)
      })
      .catch((err: unknown) => setError(err instanceof ApiCallError ? err.message : 'Failed to load the queue.'))
  }, [applied, page])

  useEffect(() => load(), [load])

  function onSearch(e: FormEvent) {
    e.preventDefault()
    setPage(0)
    setApplied({ ...draft, q: draft.q.trim() })
  }

  const waitingForReview = applied.status === 'PENDING_REVIEW'

  return (
    <section>
      <h1>Driver verification</h1>
      <VerificationTabs />
      <form className="filters" onSubmit={onSearch} role="search">
        <label>
          Search
          <input
            type="search"
            value={draft.q}
            onChange={(e) => setDraft({ ...draft, q: e.target.value })}
            placeholder="Name, phone digits or driver id"
          />
        </label>
        <label>
          Status
          <select value={draft.status} onChange={(e) => setDraft({ ...draft, status: e.target.value })}>
            {STATUS_OPTIONS.map((s) => (
              <option key={s.value} value={s.value}>
                {s.label}
              </option>
            ))}
          </select>
        </label>
        <label className="check">
          <input
            type="checkbox"
            checked={draft.overdueOnly}
            onChange={(e) => setDraft({ ...draft, overdueOnly: e.target.checked })}
          />
          Waiting over 24 hours
        </label>
        <label className="check">
          <input
            type="checkbox"
            checked={draft.readyOnly}
            onChange={(e) => setDraft({ ...draft, readyOnly: e.target.checked })}
          />
          Everything uploaded
        </label>
        <div className="row-actions">
          <button type="submit">Search</button>
        </div>
      </form>

      {error && (
        <div role="alert">
          <p>{error}</p>
          <button type="button" onClick={load}>
            Try again
          </button>
        </div>
      )}
      {!error && rows === null && <p className="muted">Loading…</p>}
      {rows !== null && rows.length === 0 && (
        <p className="muted">
          {applied.q || applied.overdueOnly || applied.readyOnly || !waitingForReview
            ? 'No captains match these filters.'
            : 'No captains are waiting for review. New applications will appear here.'}
        </p>
      )}
      {rows !== null && rows.length > 0 && (
        <>
          <p className="muted">
            {totalElements} {totalElements === 1 ? 'captain' : 'captains'}, longest wait first
          </p>
          <div className="table-scroll">
            <table className="list-table stack-table">
              <thead>
                <tr>
                  <th>Captain</th>
                  <th>Phone</th>
                  <th>City</th>
                  <th>Vehicle</th>
                  <th>Required items</th>
                  <th>Optional documents</th>
                  <th>Waiting</th>
                  <th>Review</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((row) => {
                  const items = row.requiredItems
                  return (
                    <tr key={row.driverId}>
                      <td data-label="Captain">
                        <Link to={`/driver-verification/${row.driverId}`}>
                          {row.driverName ?? `${row.driverId?.slice(0, 8)}…`}
                        </Link>
                      </td>
                      <td data-label="Phone">{row.phoneMasked ?? '—'}</td>
                      <td data-label="City">{row.city ?? '—'}</td>
                      <td data-label="Vehicle">{row.vehicleNumber ?? 'Not added'}</td>
                      <td data-label="Required items">
                        {items ? summariseItems(items) : '—'}
                      </td>
                      <td data-label="Optional documents">{row.optionalDocumentsProvided ? 'Provided' : 'None'}</td>
                      <td data-label="Waiting">
                        <span
                          className={row.overdue ? 'age-badge warn' : 'age-badge'}
                          title={row.submittedAt ? `Submitted ${new Date(row.submittedAt).toLocaleString()}` : undefined}
                        >
                          {formatWaiting(row.waitingSeconds ?? 0)}
                          {row.overdue ? ' · over 24h' : ''}
                        </span>
                      </td>
                      <td data-label="Review">
                        <Link to={`/driver-verification/${row.driverId}`}>Review</Link>
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
          {totalPages > 1 && (
            <div className="row-actions" aria-label="Pagination">
              <button type="button" onClick={() => setPage((p) => p - 1)} disabled={page === 0}>
                Previous
              </button>
              <span className="muted">
                Page {page + 1} of {totalPages}
              </span>
              <button type="button" onClick={() => setPage((p) => p + 1)} disabled={page + 1 >= totalPages}>
                Next
              </button>
            </div>
          )}
        </>
      )}
    </section>
  )
}
