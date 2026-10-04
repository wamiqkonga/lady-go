# Lady Go — Authentication & Authorization Design (Phase 3)

Status: v0.4 · **Approved 2026-10-04**; adversarial security review completed and fixes applied 2026-10-04 (§13); post-review decisions recorded (§15).

Scope: `auth` module (OTP, login, tokens, logout, admin enrolment), `security` module (JWT filter, method security, rate limiting, crypto primitives), admin password + MFA. Builds on tables already migrated in Phase 2 (`users`, `refresh_tokens`, `otp_requests`) — see `docs/database/database-design.md`. No new business rules beyond the roadmap's decided/defaulted items (D3) and the role matrix already published in `docs/product/admin-flows.md` §1.

## 1. Roles

`CUSTOMER`, `DRIVER`, `ADMIN`, `SUPER_ADMIN`, `SUPPORT` — as constrained by the `users.role` CHECK in `V2__users_auth.sql`. Permission matrix for the three staff roles is already decided in `docs/product/admin-flows.md` §1 (dashboard/incidents for SUPPORT; approvals/suspensions/ride intervention for ADMIN+; pricing/config/admin-user-management/unrestricted location history for SUPER_ADMIN only). This document does not re-derive that matrix; it only wires JWT claims and `@PreAuthorize` checks to it.

## 2. Credentials by role

| Role | Credential | Source |
|------|-----------|--------|
| CUSTOMER, DRIVER | Phone + OTP only. No password (`ck_users_password_admin_only` forbids it). | existing constraint |
| ADMIN, SUPER_ADMIN, SUPPORT | Email + password + mandatory TOTP MFA | "Admin authentication: email + password + MFA (TOTP)" — `docs/product/admin-flows.md` §1 |

Passwords: BCrypt (Spring Security default, `PasswordEncoder` bean), never logged, never returned in any response.

MFA: TOTP (RFC 6238), secret generated at enrolment, stored only as `mfa_secret_enc` (encrypted at the application layer — AES-GCM with a key from `LADYGO_MFA_ENC_KEY`, never the raw secret). `mfa_enabled` must be `true` for ADMIN/SUPER_ADMIN/SUPPORT before a normal login succeeds — enforced in code, not just assumed, since the DB CHECK only restricts the column to staff roles, not requires it.

## 3. Admin bootstrap, enrolment and lost-device recovery

There is no admin UI yet (Phase 9) and no existing admin row, so the first SUPER_ADMIN and every subsequent staff account need a path into the system that never involves an API bypass of MFA.

### 3.1 Bootstrap (first SUPER_ADMIN only)

`AdminBootstrapRunner` (an `ApplicationRunner`) runs on every startup and does nothing unless **zero** users with `role = 'SUPER_ADMIN'` exist. If none exist, it reads `LADYGO_BOOTSTRAP_ADMIN_EMAIL`, `LADYGO_BOOTSTRAP_ADMIN_PASSWORD` and `LADYGO_BOOTSTRAP_ADMIN_PHONE` (env only, never defaulted in any profile, not even dev — same treatment as `LADYGO_DB_PASSWORD`). The phone is required because `users.phone` is `NOT NULL` for every role per `V2__users_auth.sql` — staff still need a row that satisfies the schema even though phone is never a staff login factor. If any of the three is missing, it logs a warning and does nothing (does not fail startup — a running system without an admin yet is valid, e.g. CI). If all three are present, it creates exactly one `SUPER_ADMIN` row: `password_hash` = BCrypt(the given password), `must_change_password = true`, `mfa_enabled = false`. The bootstrap password is a one-time credential; the forced-change step below means it cannot be reused.

### 3.2 Enrolment (forced on first login, and for every new staff account)

`POST /api/v1/auth/admin/login { email, password, totp? }`:
1. Validate email + password. Wrong credentials → generic `UNAUTHORIZED` (rate-limited per §8, same message whether the email exists or not).
2. If valid but `must_change_password = true` **or** `mfa_enabled = false`, do **not** issue a normal access/refresh pair. Issue a short-lived (10 minute) **enrolment token** — a JWT with `scope: "ENROLMENT"` instead of a role claim — and a response body listing which steps remain (`["PASSWORD_CHANGE", "MFA_ENROLL"]` in order; MFA enrolment is never offered before the password has been changed). An enrolment token is only accepted by the three enrolment endpoints below, nothing else — enforced by the JWT filter checking `scope` before authorities are ever considered.
3. If `must_change_password = false` and `mfa_enabled = true`, `totp` is required and checked (§3.4); on success, issue the normal access/refresh pair (§5/§6).

Enrolment endpoints (all require a valid enrolment token in `Authorization`, i.e. the caller already proved the password):
- `POST /api/v1/auth/admin/password/change { currentPassword, newPassword }` → re-checks `currentPassword` (defence in depth even though the enrolment token already proves it was correct at login time), sets the new BCrypt hash, `must_change_password = false`. If MFA is already enabled (a staff member just resetting a compromised password, not a new account), this alone is enough to get a full token pair on the *next* `/admin/login`; the current enrolment token still cannot do anything else.
- `POST /api/v1/auth/admin/mfa/enroll` (only reachable once `must_change_password = false`) → generates a new TOTP secret, stores it **only** in `mfa_pending_secret_enc` (not yet `mfa_secret_enc`), returns the secret + a provisioning URI (`otpauth://totp/LadyGo:{email}?secret=...&issuer=LadyGo`) for a QR code. Not yet active — `mfa_enabled` stays `false`.
- `POST /api/v1/auth/admin/mfa/enroll/confirm { code }` → verifies `code` against `mfa_pending_secret_enc` (§3.4). On success: copy pending → `mfa_secret_enc`, clear `mfa_pending_secret_enc`, set `mfa_enabled = true`. The enrolment token is now spent (one-time; see §3.5); the admin must call `/admin/login` again, this time with a TOTP code, to get a real session.

### 3.3 Lost-device recovery (existing enrolled staff)

A SUPER_ADMIN can reset **another** staff member's MFA: `POST /api/v1/admin/users/{id}/mfa/reset` (in the `admin` module, `@PreAuthorize("hasRole('SUPER_ADMIN')")`) clears `mfa_enabled`, `mfa_secret_enc`, `mfa_pending_secret_enc` and sets nothing else — the target's next login drops into the enrolment flow at the MFA step (password is untouched, so `must_change_password` is not re-forced). This is an audited action (`audit_logs`, action `ADMIN_MFA_RESET`, `target_type = 'users'`).

**There is deliberately no API path for a lone SUPER_ADMIN to recover their own lost device.** Building one would be an MFA bypass reachable over HTTP, which defeats the point of requiring MFA. If the only SUPER_ADMIN loses their device, recovery is an out-of-band operator action directly against the database (`UPDATE users SET mfa_enabled = false, mfa_secret_enc = NULL WHERE id = ...`), run by someone with production DB access, logged outside the application (shell history / change ticket), the same trust boundary that already exists for someone who can read `LADYGO_DB_PASSWORD`. Operational guidance: provision at least two SUPER_ADMIN accounts in staging/prod specifically so one can recover the other through §3.3 instead of needing DB access. This is noted as an operations runbook item, not a code deliverable of Phase 3.

### 3.4 TOTP mechanics (shared by enrolment, login, and confirm)

RFC 6238, HMAC-SHA1, 30-second step, 6 digits, ±1 step window (accounts for clock drift). **Replay rejection**: `users.mfa_last_used_step` stores the time-step index of the last code that was *accepted* for that user; a presented code is rejected if its own step is `<= mfa_last_used_step`, even if it is otherwise mathematically valid — this closes the window where the same code could be replayed twice inside its validity period. The column is updated atomically together with the accept decision (single conditional `UPDATE ... WHERE mfa_last_used_step < :step`).

### 3.5 Enrolment token is one-time

The enrolment token carries the admin's `must_change_password`/`mfa_enabled` state as immutable claims captured at issuance (`pwdOk`, `mfaOk`) — not re-read from the DB on every call. `/mfa/enroll` and `/mfa/enroll/confirm` require `pwdOk == true` on the token itself; `/password/change` does not (it's the step that makes `pwdOk` true in the first place). Because the claims are frozen at issuance, a token obtained while `must_change_password` was still `true` can never pass the `pwdOk` check, even immediately after that same request successfully changes the password — the admin must call `/admin/login` again to receive a fresh enrolment token whose `pwdOk` claim reflects the new state. This makes each token self-invalidating as enrolment progresses, with no server-side token blacklist needed.

## 4. OTP flow (CUSTOMER / DRIVER)

```
POST /api/v1/auth/otp/request  { phone, purpose: LOGIN | PHONE_VERIFY, intendedRole: CUSTOMER | DRIVER }
POST /api/v1/auth/otp/verify   { phone, code, purpose }
```

1. **Request**: validate E.164 phone (same regex as the `users.phone` CHECK). `intendedRole` is restricted to `CUSTOMER` or `DRIVER` — never `ADMIN`/`SUPER_ADMIN`/`SUPPORT`; a request naming a staff role is rejected with `VALIDATION_FAILED` before anything else happens. Check the resend rate limit (§8) for `(phone, purpose)`. **Invalidate earlier unconsumed codes first**: `UPDATE otp_requests SET consumed_at = now() WHERE phone = :phone AND purpose = :purpose AND consumed_at IS NULL` — approved change from the draft (was: allow multiple valid codes). Then generate a 6-digit numeric code, store only `code_hash` (HMAC-SHA256 with `LADYGO_OTP_HASH_SECRET`) in a new `otp_requests` row, `expires_at = now + otp.expiry.seconds`, `attempts = 0`. Send via `SmsService` (§9). Response never includes the code or whether the phone is already registered (prevents enumeration) — generic `"If this number is valid, a code was sent."`
2. **Verify** — both steps below are single atomic conditional `UPDATE`s, not read-then-write, so two concurrent verify calls for the same code can't both succeed or double-count attempts:
   - Find the latest row for `(phone, purpose)` (at most one is ever unconsumed now, per the invalidation above).
   - `UPDATE otp_requests SET attempts = attempts + 1 WHERE id = :id AND consumed_at IS NULL AND expires_at > now() AND attempts < :maxAttempts RETURNING attempts, code_hash` — zero rows affected means "already consumed, expired, or attempts exhausted"; respond with a single generic `VALIDATION_FAILED` / `RATE_LIMITED` as appropriate, revealing nothing about which case it was.
   - Compare `code` against the returned `code_hash` in application code. On mismatch: done, the attempt was already counted by the update above, respond `VALIDATION_FAILED`.
   - On match: `UPDATE otp_requests SET consumed_at = now() WHERE id = :id AND consumed_at IS NULL` — if this affects zero rows, another concurrent verify call won the race and already consumed it; treat as failure and issue nothing (prevents double token issuance from a replayed correct code hitting the server twice). If it affects one row, proceed to issue tokens (LOGIN) or mark `users.phone_verified_at` (PHONE_VERIFY).
3. **Existing user of a different role** (OQ-8 below): if the phone already belongs to a `User` whose `role` differs from the request's `intendedRole` (e.g. a DRIVER account tries to log in through the customer app's `intendedRole: CUSTOMER`), verify fails with a distinct, still-generic error (`VALIDATION_FAILED`, no detail in the body) and the attempt is logged server-side as `operation=otp_role_mismatch` for support follow-up. No account switching, no silent merge.
4. New-user creation on first LOGIN verify is phone-only (`name`/`email` left null; profile completion deferred to Phase 4/5), role = the request's `intendedRole`.

Configurable (via the existing `configuration` table / `ConfigurationService` — not hard-coded per `docs/architecture/backend-architecture.md` §1 "no hard-coded business values"): `otp.expiry.seconds` = 300, `otp.max.attempts` = 5, `otp.resend.cooldown.seconds` = 30, `otp.resend.max.per.hour` = 5. Seeded in `V12` so they exist from the first deploy.

## 5. JWT access tokens

- Algorithm: HS256, signing key from `LADYGO_JWT_SECRET` (env only, never committed; startup fails if unset in staging/prod — same pattern as `LADYGO_CORS_ALLOWED_ORIGINS`; a clearly-labelled insecure default exists only in `application.yml`'s base/dev defaults).
- Claims: `sub` (user id), `role` (absent on enrolment tokens, replaced by `scope: "ENROLMENT"` — see §3.2), `iat`, `exp`, `jti` (random, logged on issuance/validation failure for audit, not persisted).
- TTL: 15 minutes for CUSTOMER/DRIVER, 10 minutes for ADMIN/SUPER_ADMIN/SUPPORT ("short sessions" — `docs/product/admin-flows.md` §1), 10 minutes for enrolment tokens.
- Stateless validation only — no DB hit per request. Revocation works at the refresh-token layer (§6) plus the short TTL above: access tokens are not individually revocable.
- `JwtAuthenticationFilter` in `security/` populates `SecurityContext` with authorities `ROLE_<role>` (or no authorities + a request attribute marking `ENROLMENT` scope, checked explicitly by the three enrolment endpoints rather than through `hasRole`); `@EnableMethodSecurity` + `@PreAuthorize("hasRole('ADMIN')")` etc. at the service layer per `docs/architecture/backend-architecture.md` layering.

## 6. Refresh tokens: rotation + reuse detection

Uses `refresh_tokens` exactly as migrated (`token_hash`, `device_id`, `expires_at`, `revoked_at`, `replaced_by`). Not issued for enrolment tokens — only a real login (password+TOTP, or OTP verify) produces a refresh token.

- Raw token is a random 256-bit value returned once to the client; only its SHA-256 hash (`token_hash`) is stored — never reversible, so a DB leak doesn't hand out usable tokens.
- **Rotation**: `POST /api/v1/auth/refresh { refreshToken }` → hash it, look up by `token_hash`.
  - Not found → `UNAUTHORIZED`.
  - Found, `revoked_at` already set → **reuse detected**: walk the chain (`replaced_by` forward, and backward via tokens whose `replaced_by = this.id`) and revoke every token in the family for that `user_id` + `device_id`, forcing re-login on that device. Logged as a security event (`operation=refresh_reuse_detected`).
  - Found, valid, not expired, not revoked → issue new access + refresh token pair, set this row's `revoked_at = now`, `replaced_by = <new token id>`. The new token keeps the same `device_id`.
  - Found but `expires_at` passed → `UNAUTHORIZED`, no reuse action (just an expired credential, not a theft signal).
- Absolute lifetime: `refresh.token.ttl.days` = 30 (configuration-table default, seeded in `V12`). No silent sliding-window extension beyond that absolute expiry.
- `device_id` is client-supplied (installation ID), not verified cryptographically; it only scopes which sibling sessions get revoked on reuse, it is not a security boundary by itself.

## 7. Logout

`POST /api/v1/auth/logout { refreshToken }` (access token via `Authorization` header as usual) → hash the given refresh token, set `revoked_at = now` on that row only (not the whole family — logout is a legitimate single-device action, unlike reuse detection).

`logoutAll` flag (or an admin suspending a user) revokes every non-revoked `refresh_tokens` row for `user_id`. **Approved**: suspension (`users.status → SUSPENDED`) automatically revokes all of that user's refresh tokens server-side as part of the same transaction, wired now even though the admin UI for triggering suspension is Phase 9 — the revoke-on-suspend hook lives in `auth`'s `RefreshTokenService` and is called from wherever `UserStatus` is set to `SUSPENDED`.

## 8. Rate limiting — database-backed, not Redis, for Phase 3

**Decision: database-backed.** Reasoning:
- Redis exists in `infrastructure/docker-compose.yml` but is not yet wired into the Spring Boot app — no `spring-boot-starter-data-redis` dependency, no `RedisConfig`, nothing in `application*.yml`. The roadmap brings Redis in as load-bearing infrastructure in Phase 6 (matching, driver GEO index) and Phase 7 (real-time), per `docs/architecture/backend-architecture.md` §5.
- Pulling Redis forward into Phase 3 only to rate-limit a handful of auth endpoints adds an operational dependency ahead of actual need.
- Traffic at this phase is a single backend instance; a Postgres-backed fixed-window counter is correct and fast enough, and reuses the one datastore already proven in CI.
- Revisit in Phase 6/7: once Redis is wired for matching, the same rate limiter can move to Redis `INCR`+`EXPIRE`; it is isolated behind a `RateLimiter` interface now so that swap is mechanical later.

New table (migration `V12`):

```sql
CREATE TABLE auth_rate_limits (
    bucket_key    varchar(200) PRIMARY KEY,   -- e.g. "otp_request:+919812345678", "admin_login_email:a@b.com"
    window_start  timestamptz  NOT NULL,
    attempt_count integer      NOT NULL DEFAULT 1,
    updated_at    timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX idx_auth_rate_limits_window ON auth_rate_limits (window_start);
```

Atomic increment via `INSERT ... ON CONFLICT (bucket_key) DO UPDATE SET attempt_count = CASE WHEN auth_rate_limits.window_start < :newWindowStart THEN 1 ELSE auth_rate_limits.attempt_count + 1 END, window_start = CASE WHEN auth_rate_limits.window_start < :newWindowStart THEN :newWindowStart ELSE auth_rate_limits.window_start END, updated_at = now() RETURNING attempt_count, window_start` — single round trip, no read-then-write race.

Buckets: OTP resend (per phone+purpose), admin login (per email **and** per IP — both checked, whichever trips first wins), refresh/logout abuse (per user). OTP verify attempts reuse `otp_requests.attempts`, no separate bucket. Limits are configuration-table values (admin login: 5 attempts / 15 min, seeded in `V12`), not hard-coded.

**Client IP — trusted proxies only.** `X-Forwarded-For` is attacker-controlled unless the immediate TCP peer is a proxy we run, so it cannot be trusted blindly for a security control like rate limiting (a client could set it to someone else's IP to either dodge their own limit or frame another IP). `ClientIpResolver`: read `ladygo.security.trusted-proxies` (CIDR list, configuration property, empty by default — nothing is trusted until explicitly configured for the real deployment topology, e.g. the load balancer's subnet). If the request's actual remote address matches a trusted proxy, take the first (client-side) hop out of `X-Forwarded-For`; otherwise use the remote address directly and ignore any forwarded header entirely. This resolver is shared by every IP-keyed rate-limit bucket, not reimplemented per endpoint.

## 9. SMS provider abstraction + mock

Per `docs/architecture/backend-architecture.md` §7 (`NotificationChannel` SPI family):

```java
public interface SmsService {
    void sendOtp(String phoneE164, String code, Duration validFor);
}
```

- `MockSmsService`, `@Profile({"dev", "test"})`: logs the code at INFO and never contacts a real carrier. Because it is profile-gated, it is structurally impossible for it to load as the active `SmsService` bean under `staging`/`prod`.
- **Approved addition**: a startup check fails fast, loudly, before the app accepts traffic, if `staging`/`prod` is active and no real `SmsService` bean is available — i.e., today, staging/prod cannot start at all until a real provider adapter is implemented, which is intentional (no silent fallback to logging OTP codes in a real environment). Implemented as a `@Profile({"staging", "prod"})` bean of type `SmsService` that must be supplied by a real adapter; if none is registered, Spring's own "no qualifying bean" failure already stops startup — made explicit and documented rather than left as an obscure autowiring error, with a one-line `ApplicationContextInitializer`-level check that throws a clear `IllegalStateException` naming the missing provider if one ever gets accidentally made profile-unrestricted in the future.
- Real provider (e.g. Twilio, MSG91 — D3 decided the abstraction + mock-in-dev pattern; which live vendor to contract is a business/procurement decision, not resolved here) implements the same interface, credentials from environment (`LADYGO_SMS_PROVIDER`, `LADYGO_SMS_API_KEY`, ...) — never in `application*.yml` literals.
- Delivery failures are logged to `notification_logs` (table already migrated in V11) but never block the OTP row from being created.

## 10. Secrets and configuration — no secrets in the repo

| Variable | Purpose | Required in |
|---|---|---|
| `LADYGO_JWT_SECRET` | HS256 signing key for access/enrolment tokens | staging, prod (dev has an insecure placeholder default) |
| `LADYGO_MFA_ENC_KEY` | AES-GCM key encrypting `mfa_secret_enc` / `mfa_pending_secret_enc` | staging, prod (dev placeholder default) |
| `LADYGO_OTP_HASH_SECRET` | HMAC key for OTP code hashing | staging, prod (dev placeholder default) |
| `LADYGO_BOOTSTRAP_ADMIN_EMAIL`, `LADYGO_BOOTSTRAP_ADMIN_PASSWORD`, `LADYGO_BOOTSTRAP_ADMIN_PHONE` | one-time first-SUPER_ADMIN creation (§3.1); phone is required only to satisfy the `users.phone NOT NULL` schema constraint, not used as a staff login factor | set once, anywhere, then should be unset/rotated |
| `LADYGO_SMS_PROVIDER`, `LADYGO_SMS_API_KEY`, ... | real SMS provider credentials | staging, prod (no dev default — mock is used instead) |

`ladygo.security.trusted-proxies` (§8) is a deployment-topology setting, not a secret, but still environment-driven (no hard-coded IPs).

## 11. Testing (mandatory, no weakening of Phase 2 constraints)

- **Unit**: OTP hashing, atomic-update repository methods (mocked), TOTP generation/verification including replay rejection at the exact boundary step, refresh-token rotation state machine (including reuse detection), JWT issuance/parsing/expiry/enrolment-scope, password hashing, `ClientIpResolver` with/without a trusted-proxy match, admin bootstrap (runs only when zero SUPER_ADMIN exist; no-ops otherwise).
- **Repository** (Testcontainers): new `users` columns/CHECK (`must_change_password`, `mfa_pending_secret_enc` staff-only), `auth_rate_limits` upsert race behaviour, `EnumSchemaParityTest` unaffected (no new enums).
- **Service/integration** (MockMvc + real Postgres via Testcontainers): full OTP request→verify→refresh→reuse-detected→logout happy and abuse paths, including concurrent-verify double-submit returning exactly one success; full admin bootstrap→forced password change→MFA enrol→confirm→real login path; admin login lockout at the configured attempt threshold; OTP role-mismatch rejection (OQ-8); rate-limit buckets blocking at threshold; SMS startup guard (context fails to start with a `staging`/`prod` profile and no real `SmsService`).
- **Security/authorisation matrix**: every new endpoint tested against each role per `docs/product/admin-flows.md` §1, plus the MFA-reset endpoint restricted to SUPER_ADMIN.
- CI gate: all of the above run in the existing `mvn -B verify` Backend workflow step; no new CI job needed.

## 12. Resolved decisions (were open questions, now settled)

| # | Decision |
|---|----------|
| OQ-1 | OTP codes hashed with HMAC-SHA256 + `LADYGO_OTP_HASH_SECRET`. |
| OQ-2 | **Changed from the draft.** A new `/otp/request` invalidates (consumes) any earlier unconsumed code for the same phone+purpose. No schema change — implemented as a plain `UPDATE ... SET consumed_at = now()` before inserting the new row. |
| OQ-3 | Defaults as proposed: OTP expiry 5 min, 5 attempts, 30s resend cooldown, 5 resends/hour; refresh TTL 30 days; admin login lockout 5 attempts/15 min per email+IP. Seeded into `configuration` by `V12`. |
| OQ-4 | Access token TTL 15 min (CUSTOMER/DRIVER), 10 min (staff + enrolment tokens). |
| OQ-5 | Phone-only account creation on first OTP login; profile completion deferred to Phase 4/5. |
| OQ-6 | Suspension automatically revokes all refresh tokens server-side, wired in Phase 3 even though the admin UI trigger is Phase 9. |
| OQ-7 | No step-up MFA re-verification in Phase 3 — login-time MFA only. **Action taken**: `docs/development/roadmap.md` Phase 9 now notes that destructive admin actions (pricing/config changes, admin user management per `docs/product/admin-flows.md` §1) need step-up MFA re-verification before that admin UI ships, so it isn't silently forgotten. |
| OQ-8 | **Decided 2026-10-04**: one role per phone number for the MVP — the current `uq_users_phone` behaviour (flat rejection on `intendedRole` mismatch, §4.3) is correct as implemented, not a gap. On mismatch, the response changes from a fully generic error to a clear, bounded message: *"This number is already registered for the other app."* — enough for a legitimate user to understand what happened and go use the right app, without revealing the account's role, status, or any other detail. No schema change (`(phone, role)` composite uniqueness is explicitly rejected, not deferred). |

## 13. Adversarial security review (2026-10-04)

A fresh agent with no visibility into this implementation session was given only this document plus the `auth`/`security` code and tests, and told to try to break it against the checklist the project owner specified. Findings, in severity order:

| Finding | Severity | Fixed | Test |
|---|---|---|---|
| `users.status` never checked — a SUSPENDED/DELETED user could keep completing OTP login, admin login, and refresh indefinitely | CRITICAL | Yes — status gate added to `OtpLoginService.verifyOtp`, `AdminAuthService.login`, `RefreshTokenService.rotate` (the last also revokes the device family) | `OtpLoginServiceIntegrationTest.suspendedUserCannotLogInEvenWithTheCorrectCode` / `.deletedUserCannotLogInEvenWithTheCorrectCode`; `AdminAuthServiceIntegrationTest.suspendedAdminCannotLogInEvenWithCorrectCredentials`; `RefreshTokenServiceIntegrationTest.suspendedUsersRefreshTokenIsRejectedAndTheWholeDeviceFamilyIsRevoked` |
| `ClientIpResolver` returned the left-most (client-suppliable) `X-Forwarded-For` hop instead of the right-most non-proxy hop — spoofable even behind a correctly configured trusted proxy, defeating the whole point of §8 | HIGH | Yes — now walks from the right, skipping trusted hops | `ClientIpResolverTest.spoofedClientSuppliedHopIsIgnoredWhenTheProxyAppendsTheRealIp`, `.multiHopChainSkipsEveryTrustedProxyInTheChain` |
| Enrolment-scoped JWTs satisfied the blanket `anyRequest().authenticated()` rule and could reach any endpoint without a role-specific `@PreAuthorize` (e.g. `/auth/logout`), contradicting this document's §3.2 claim that they are confined to the three enrolment endpoints | HIGH | Yes — `JwtAuthenticationFilter` now refuses to authenticate an enrolment-scoped token outside those three paths; everywhere else it's as if no token were presented | `AdminAuthControllerIntegrationTest.enrolmentTokenCannotBeUsedOnNonEnrolmentEndpoints` |
| Admin login rate limiting consumed a slot on every call including successes, so enough legitimate logins in one window self-locked the account | MEDIUM | Yes — `RateLimiter.release()` gives back the slot on a successful login (not on failures, so brute-force protection is unaffected) | `AdminAuthServiceIntegrationTest.successfulLoginsDoNotSelfLockTheAccount`; `DatabaseRateLimiterIntegrationTest.consumeThenReleaseStaysWithinBudgetAcrossManyCalls`, `.releaseDoesNotGoBelowZero` |
| No grace window on refresh-token rotation: a dropped response after a successful rotation means the client's retry with the old (now-revoked) token is indistinguishable from theft and revokes the whole device family, forcing re-login | MEDIUM | **Decided 2026-10-04, see §15** — accepted for now, bounded grace window before mobile beta | — |
| Admin login lockout is keyed per-email with no IP component, so an attacker who merely knows a staff member's email can lock that account for the full window with 5 wrong-password requests, no auth needed | MEDIUM | **Decided 2026-10-04, see §15** — accepted for now, progressive delay + alerting + unlock path before production | — |

Checked and found correct (no change): TOTP replay rejection at the exact ±1-step window boundary; concurrent OTP verification (double-consume/double-count); multi-request OTP invalidation; role-confusion/staff-role-escalation paths; the bulk-update-vs-stale-entity bug class (grepped every `@Modifying` query and call site — no other instance beyond the one already fixed before this review); mock SMS confinement to dev/test; no secrets in logs/responses/fixtures; JWT algorithm/expiry/claim handling; the `@PreAuthorize` surface against `docs/product/admin-flows.md` §1 (todo as more endpoints ship in later phases — see the enrolment-scope finding above for why this is easy to regress).

A second, automated review of the pushed fix commit caught two more real issues in the fixes themselves:

| Finding | Severity | Fixed | Test |
|---|---|---|---|
| `ClientIpResolver.matches()` called `InetAddress.getByName()` on an attacker-controlled `X-Forwarded-For` entry with no format check first — for anything that isn't an IP literal, that performs a real blocking DNS lookup to a name the attacker chose (request-thread DoS, plus an outbound-DNS side channel) | HIGH | Yes — rejects any non-IP-literal candidate before any lookup, no network call | `ClientIpResolverTest.nonIpLiteralHopIsRejectedWithoutADnsLookup` |
| The first version of the login-rate-limit fix released the **IP** bucket on any success, not just the email bucket — on a shared IP (office NAT, carrier CGNAT), one account's legitimate login would wipe out an attacker's accumulated failed attempts against a completely different victim account on that same IP: a cross-account rate-limit bypass | HIGH | Yes — only the email bucket (exclusively one account's) is ever released; the IP bucket (shared) is never released | `AdminAuthServiceIntegrationTest.successfulLoginByOneAccountDoesNotResetAnotherAccountsFailuresOnTheSameIp` |

Both were caught before merge, fixed the same way as everything above (regression test first, fix, verify the test fails without it), and are reflected in the "checked and found correct" list going forward — the `@Modifying` grep and the rate-limit-bypass checks should be re-run on any future change to `ClientIpResolver` or the rate-limit release logic specifically.

No existing test was weakened or deleted to make this pass. One pre-existing test (`DatabaseRateLimiterIntegrationTest.windowExpiryResetsTheCount`) had a timing margin tight enough to flake under a cold Testcontainers start, unrelated to this review's findings; its margins were widened (50ms/80ms → 500ms/800ms), not its assertions.

## 15. Post-review decisions (2026-10-04) — the two accepted tradeoffs in §13

| # | Decision |
|---|----------|
| Refresh-token grace window | Keep the current strict behaviour (any reuse of an already-rotated token revokes the whole device family, no exceptions) through initial launch. **Before mobile beta**: add a bounded ~10 second grace window — a request presenting an already-rotated token is treated as a safe retry (return the *successor* token pair again, no revocation) **only if** that successor token is still unused and the rotation happened within the grace window; a second, later reuse (successor already consumed) still revokes the family immediately as reuse, as does any reuse past the grace window. This bounds the reopened race to "attacker and legitimate client both retry within ~10s of the same rotation," not an unlimited window. |
| Admin per-email lockout | Accept the current hard per-(email, IP) lockout for now — no real staff accounts exist in production yet, so there is no victim to target. **Before production**: replace the hard lockout with progressive delay (exponential backoff per `(email, IP)` pair rather than a flat block), a failure alert to the account owner and to ops once a threshold is crossed, and a `SUPER_ADMIN` unlock path (parallel to the existing MFA-reset endpoint in `com.ladygo.admin.web.AdminUserController`) so a locked-out legitimate admin isn't stuck waiting out the window. |

Both are now tracked in `docs/development/roadmap.md` against the phases where they must land before being load-bearing (mobile beta / production), not left as implicit assumptions in this document alone.

No code has been written beyond what Phase 2 already migrated and what this section now documents as reviewed; the implementation in this document and in `backend/src/main/java/com/ladygo/{auth,security}` is complete for Phase 3.
