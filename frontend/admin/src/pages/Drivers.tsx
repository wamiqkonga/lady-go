import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { client, unwrap, ApiCallError } from '../lib/client'
import type { components } from '../lib/api-types'

type DriverRow = components['schemas']['AdminDriverListItemResponse']
type VerificationStatus = NonNullable<DriverRow['verificationStatus']>

const STATUSES: VerificationStatus[] = ['NOT_SUBMITTED', 'PENDING_REVIEW', 'APPROVED', 'REJECTED', 'SUSPENDED']
const PAGE_SIZE = 20

export default function Drivers() {
  const [rows, setRows] = useState<DriverRow[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [draftQuery, setDraftQuery] = useState('')
  const [draftStatus, setDraftStatus] = useState('')
  const [applied, setApplied] = useState({ q: '', status: '' })
  const [page, setPage] = useState(0)
  const [totalPages, setTotalPages] = useState(0)

  const load = useCallback(() => {
    setError(null)
    client
      .GET('/api/v1/admin/drivers', {
        params: {
          query: {
            q: applied.q || undefined,
            verificationStatus: applied.status ? (applied.status as VerificationStatus) : undefined,
            pageable: { page, size: PAGE_SIZE },
          },
        },
      })
      .then((result) => {
        const data = unwrap(result)
        setRows(data.content ?? [])
        setTotalPages(data.totalPages ?? 0)
      })
      .catch((err: unknown) => setError(err instanceof ApiCallError ? err.message : 'Failed to load drivers.'))
  }, [applied, page])

  useEffect(() => load(), [load])

  function onSearch(e: FormEvent) {
    e.preventDefault()
    setRows(null)
    setPage(0)
    setApplied({ q: draftQuery.trim(), status: draftStatus })
  }

  return (
    <section>
      <h1>Drivers</h1>
      <form className="filters" onSubmit={onSearch} role="search">
        <label>
          Search
          <input
            type="search"
            value={draftQuery}
            onChange={(e) => setDraftQuery(e.target.value)}
            placeholder="Name, phone or driver id"
          />
        </label>
        <label>
          Status
          <select value={draftStatus} onChange={(e) => setDraftStatus(e.target.value)}>
            <option value="">All</option>
            {STATUSES.map((s) => (
              <option key={s} value={s}>
                {s}
              </option>
            ))}
          </select>
        </label>
        <div className="row-actions">
          <button type="submit">Search</button>
        </div>
      </form>

      {error && <p role="alert">{error}</p>}
      {!error && rows === null && <p className="muted">Loading…</p>}
      {rows !== null && rows.length === 0 && <p className="muted">No drivers match.</p>}
      {rows !== null && rows.length > 0 && (
        <>
          <div className="table-scroll">
            <table className="list-table stack-table">
              <thead>
                <tr>
                  <th>Driver</th>
                  <th>Phone</th>
                  <th>Verification</th>
                  <th>Availability</th>
                  <th>Rating</th>
                  <th>Rides</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((row) => (
                  <tr key={row.id}>
                    <td data-label="Driver">
                      <Link to={`/drivers/${row.id}`}>{row.name ?? `${row.id?.slice(0, 8)}…`}</Link>
                    </td>
                    <td data-label="Phone">{row.phoneMasked ?? '—'}</td>
                    <td data-label="Verification">{row.verificationStatus}</td>
                    <td data-label="Availability">{row.availabilityStatus}</td>
                    <td data-label="Rating">{row.ratingAvg?.toFixed(2) ?? '—'}</td>
                    <td data-label="Rides">{row.totalRides ?? 0}</td>
                  </tr>
                ))}
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
