import { useCallback, useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import ReasonAction from '../components/ReasonAction'
import { ApiCallError, client, expectOk, unwrap } from '../lib/client'
import { STAFF_ROLES, statusLabel, type StaffRole, type StaffUser } from '../lib/staff'

export default function AdminUserDetail() {
  const { id = '' } = useParams()
  const [user, setUser] = useState<StaffUser | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [newRole, setNewRole] = useState<StaffRole>('SUPPORT')

  const load = useCallback(() => {
    setError(null)
    client
      .GET('/api/v1/admin/users/{id}', { params: { path: { id } } })
      .then((result) => {
        const loaded = unwrap(result)
        setUser(loaded)
        const current = loaded.role as StaffRole
        setNewRole(STAFF_ROLES.find((r) => r !== current) ?? 'SUPPORT')
      })
      .catch((err: unknown) => setError(err instanceof ApiCallError ? err.message : 'Failed to load this account.'))
  }, [id])

  useEffect(() => load(), [load])

  if (error) {
    return (
      <section>
        <p role="alert">{error}</p>
        <Link to="/users">Back to staff accounts</Link>
      </section>
    )
  }
  if (!user) return <p className="muted">Loading…</p>

  const active = user.status === 'ACTIVE'
  const path = { params: { path: { id } } }

  return (
    <section>
      <p>
        <Link to="/users">← Staff accounts</Link>
      </p>
      <h1>{user.name || 'Unnamed'}</h1>
      <dl className="panel detail-list">
        <dt>Email</dt>
        <dd>{user.maskedEmail}</dd>
        <dt>Role</dt>
        <dd>{user.role}</dd>
        <dt>Status</dt>
        <dd>{statusLabel(user)}</dd>
        <dt>Authenticator</dt>
        <dd>{user.mfaEnrolled ? 'Enrolled' : 'Not set up'}</dd>
        <dt>Last sign-in</dt>
        <dd>{user.lastLoginAt ? new Date(user.lastLoginAt).toLocaleString() : 'Never'}</dd>
        <dt>Sign-in</dt>
        <dd>{user.locked ? 'Locked after failed attempts' : 'Not locked'}</dd>
      </dl>

      <h2>Actions</h2>
      <p className="muted">Each action needs a written reason and a fresh authenticator code, and is recorded in the audit log.</p>
      <div className="staff-actions">
        <ReasonAction
          label="Change role"
          description={`Currently ${user.role}. The person is signed out everywhere and signs in again with the new role.`}
          perform={async (reason) =>
            unwrap(await client.POST('/api/v1/admin/users/{id}/role', { ...path, body: { role: newRole, reason } }))
          }
          onDone={load}
        >
          <label htmlFor="new-role">New role</label>
          <select id="new-role" value={newRole} onChange={(e) => setNewRole(e.target.value as StaffRole)}>
            {STAFF_ROLES.filter((r) => r !== user.role).map((r) => (
              <option key={r} value={r}>
                {r}
              </option>
            ))}
          </select>
        </ReasonAction>

        {active ? (
          <ReasonAction
            label="Deactivate"
            description="They are signed out everywhere and cannot sign in until reactivated."
            perform={async (reason) =>
              unwrap(await client.POST('/api/v1/admin/users/{id}/deactivate', { ...path, body: { reason } }))
            }
            onDone={load}
          />
        ) : (
          <ReasonAction
            label="Reactivate"
            description="They can sign in again with their existing password and authenticator."
            perform={async (reason) =>
              unwrap(await client.POST('/api/v1/admin/users/{id}/reactivate', { ...path, body: { reason } }))
            }
            onDone={load}
          />
        )}

        {user.locked && (
          <ReasonAction
            label="Unlock sign-in"
            description="Clears the wait after failed sign-in attempts."
            perform={async (reason) => expectOk(await client.POST('/api/v1/admin/users/{id}/unlock', { ...path, body: { reason } }))}
            onDone={load}
          />
        )}

        {user.mfaEnrolled && (
          <ReasonAction
            label="Reset authenticator"
            askReason={false}
            description="Removes their authenticator so they can set up a new one (lost phone). Only do this after confirming who is asking."
            perform={async () => expectOk(await client.POST('/api/v1/admin/users/{id}/mfa/reset', path))}
            onDone={load}
          />
        )}
      </div>
    </section>
  )
}
