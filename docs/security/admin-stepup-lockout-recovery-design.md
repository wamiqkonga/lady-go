# Admin step-up MFA, lockout hardening and password recovery: design note (DRAFT)

Date: 2026-10-31. Status: awaiting owner approval. Covers plan steps 3, 4 and 4b in
`docs/development/plans/2026-10-31-admin-portal-completion.md`. Closes `docs/security/auth-design.md` OQ-7.

## 1. What the recon found (checked in code)

- Admins live in `users` (role ADMIN / SUPER_ADMIN / SUPPORT) with `password_hash`, `mfa_secret_enc`,
  `mfa_enabled`, and `status` in (ACTIVE, SUSPENDED, DELETED). Customers and drivers are also `users` rows,
  so **customer suspend/activate needs no new column**. Drivers already have `verification_status` and
  `availability_status` values of SUSPENDED (V4).
- Login (`AdminAuthService.login`) is rate limited by `RateLimiter`: 5 attempts per 15 minutes per email AND per IP
  (`admin.login.max.attempts`, `admin.login.window.minutes`, V12). Hard block, no delay curve, no alert, no unlock.
- TOTP verify already has replay protection (accepted step is remembered). Step-up reuses `TotpService`.
- `drivers.current_location` and `current_location_at` hold last known position (V4). The live map can poll this.
  Location history is only per active ride (V7) and stays out of scope.
- Only `/password/change` exists, which needs the current password.

## 2. Step-up MFA

Rule: a destructive endpoint requires a TOTP verification newer than `admin.stepup.window.minutes` (default 5).

Protected actions: config PUT, driver and customer suspend/activate, admin user changes, MFA reset, password
reset of another admin, safety incident resolve is NOT protected (speed matters in an emergency).

Flow:
1. Client calls a protected endpoint. Server finds no fresh step-up and answers `403` with code `STEP_UP_REQUIRED`.
2. UI shows a 6-digit prompt. It calls `POST /api/v1/auth/admin/step-up` with the code.
3. Server verifies TOTP (with the existing replay guard), records `now` as the step-up time, answers 204.
4. UI retries the original request once. Server now passes it.

Where the step-up time is stored (decision for you):
- **Recommended: per session.** Store it against the refresh-token family / session, so a stolen access token on
  another device does not inherit your fresh step-up. Needs a session id in the access token; I confirm this is
  available at the start of step 3.
- Fallback: one column `users.stepup_verified_at`. Simpler, but any session of that admin benefits from a fresh
  step-up inside the 5 minute window.

Enforcement is a method-level check (one annotation, `@RequiresStepUp`) on the controller methods, so the
server is the control. The UI prompt is only convenience. A test per protected endpoint proves it returns
`STEP_UP_REQUIRED` without a fresh step-up, even for SUPER_ADMIN.

If MFA is optional (`LADYGO_ADMIN_MFA_REQUIRED=false`) and the admin has no TOTP enrolled, step-up cannot work.
Rule: protected actions then require enrolment first (`403 MFA_ENROLMENT_REQUIRED`). Destructive actions are
never allowed without MFA, even when login MFA is optional. **This is a behaviour change from the last commit's
optional-MFA setting, so please confirm.**

## 3. Lockout hardening

Replace the hard block with a progressive delay and visibility:
- Failures per (email) double a required wait: 1s, 2s, 4s ... capped at 15 minutes. Failures per IP use a larger
  threshold. Success clears the email counter (IP counter behaviour stays as today, see the existing comment).
- Wrong-credentials and unknown-email responses stay identical (no account enumeration), including timing.
- Alert: log a structured `admin_login_failures_high` event at N failures in M minutes (defaults 10 in 10).
  Real alerting channel is a later ticket; the log line is the hook.
- SUPER_ADMIN unlock: `POST /api/v1/admin/users/{id}/unlock`, step-up required, audit entry, reason mandatory.
- Break-glass: if the only SUPER_ADMIN is locked, the server script in section 4 also clears the wait and the fixed-window counter for that admin, so the link it prints works at once.
- Rollout safety: new behaviour behind `admin.login.progressive.enabled`. It ships OFF (`false`) in the migration so
  deploying changes nothing; flip it on staging, test, then flip it on production. Old fixed-window limit (5 per 15
  minutes per email and per IP) stays available while it is off.

Built (step 4, migration V37): table `admin_login_throttle` (one row per email, atomic upsert, cleared by a success or an
unlock, rows older than 24 hours are pruned). Wait after the n-th failure is `min(2^(n-1), admin.login.max.delay.seconds)`
seconds. While on, the IP counter is a hard cap of four times `admin.login.max.attempts` per window (default 20 per 15
minutes) and is never released. Email matching ignores case and surrounding spaces. The alert line carries a 12-character
SHA-256 prefix of the email rather than the address, because the value is attacker-supplied; hash a known address to match.
Settings: `admin.login.progressive.enabled`, `.max.delay.seconds`, `.alert.failures`, `.alert.window.minutes`.

Counting fix (applies with the switch on or off): `AdminAuthService.login` and `.stepUp` used a plain transaction, so each
rejected attempt rolled back its own counter and nothing ever accumulated. They now keep the transaction on the three
rejection exceptions (`noRollbackFor`). Every rejection happens before any write other than the counters; the replay
check was moved ahead of the last-login write to keep that true. The step-up limit stays the fixed window per user.

## 4. Password recovery

Email link (`POST /auth/admin/password/forgot`, `POST /auth/admin/password/reset`):
- `forgot` always returns the same message and takes the same time, whether or not the email exists. Rate limited
  per email and per IP. Only ACTIVE admin-role users receive a link.
- Token: 32 random bytes, only its SHA-256 stored (table `admin_password_resets`: user_id, token_hash, expires_at,
  used_at). Expires in 30 minutes (configurable, 5 to 120), single use. A new request does not cancel an earlier open link (otherwise anyone knowing an address could keep its owner from using the link they were sent); a successful reset closes every open link.
- `reset` needs token + new password + current TOTP code if MFA is enrolled. If MFA is not enrolled the reset
  works with the token alone, and the admin must enrol MFA at next login (matches today's forced-enrolment).
- On success: password policy checked, password hash replaced, an unfinished authenticator enrolment cleared, ALL refresh
  tokens revoked (`revokeAll`), audit entry `ADMIN_PASSWORD_RESET`, a "your password was changed" email sent to the account.
- The link points to the admin portal over HTTPS only. The token is in the URL fragment, not a query string, so it
  does not reach server logs or Referer headers.

Break-glass script `scripts/ops/admin-reset-link.sh` (built; changed from the original "set a password" idea):
- Runs on the server by the owner only. It issues a normal one-time reset link straight into `admin_password_resets`
  (hash only, 30 minutes, older open links closed, operator and host recorded in the audit entry) and prints it, plus an audit entry
  `ADMIN_PASSWORD_RESET_LINK_ISSUED`. Nobody's password or hash ever passes through the script, and the link goes
  through the same `/reset-password` page, so a TOTP code is still required if the account has an authenticator.
- Refuses to run without `--confirm <same email>`, refuses anything that is not a plain email address, and only acts on
  an ACTIVE admin-role account. A lost authenticator is a separate case: another SUPER_ADMIN resets MFA first.
- Why: a script that wrote a password hash would need the app's password encoder in shell and would put a password on
  the command line or in history; issuing a link reuses the one reviewed code path.

Owner decision (2026-11-01): a SUPER_ADMIN with no authenticator may reset with the emailed link alone, so the
break-glass path stays usable during the pilot. This is accepted, not an oversight.

Known limitation: an admin with no authenticator (the pilot setting `LADYGO_ADMIN_MFA_REQUIRED=false`) can reset with
the emailed link alone, so access to that mailbox is access to the admin account. Mitigation: set up the authenticator on
the Security page, which also makes resets require a code. Reset links go only to the address stored on the account.
Config: `LADYGO_ADMIN_WEB_BASE_URL` (https, or localhost for dev) decides where the link points; blank turns the email off
and logs a warning at startup instead of failing the deploy. The email send is asynchronous so response time does not
reveal whether an address exists.

## 5. Migrations (additive only)

1. `admin_password_resets` table (V36, built).
2. (Dropped: the step-up time travels in the access token, so no column is needed.)
3. Config rows: `admin.stepup.window.minutes`, `admin.login.progressive.enabled`,
   progressive delay settings. No change to existing rows.

All run on staging first with a DB snapshot (ticket T4).

## 6. Risks and how each is handled

| Risk | Handling |
|------|----------|
| Step-up bypass | Server-side annotation, test per endpoint, no client flag trusted. |
| Reset link account takeover | Hashed single-use token, 30 min, TOTP still required, sessions revoked, notify email. |
| Locking the only admin out | Progressive delay not hard block, SUPER_ADMIN unlock, break-glass script, feature flag. |
| Email down during recovery | Break-glass script. |
| Optional MFA vs step-up | Destructive actions require MFA enrolment (needs your confirmation). |

## 7. Test list (failing test first)

Step-up: missing, expired, valid, wrong code, replayed code, each protected endpoint. Lockout: delay curve, success
clears, identical responses for unknown email, IP counter, unlock endpoint. Recovery: unknown email same reply and
timing class, expired / reused / superseded token, wrong TOTP, password policy, sessions revoked, notification sent,
rate limit. Then real-browser runs: login with TOTP, config edit with expired step-up, forgot-password end to end,
mobile width.

## 8. Needs your confirmation

1. Step-up stored per session (recommended) or per user.
2. Destructive actions require MFA enrolment even when login MFA is optional (recommended: yes).
3. Safety incident resolve is exempt from step-up (recommended: yes).
