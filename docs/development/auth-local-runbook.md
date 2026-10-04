# Lady Go — Local Auth Runbook

Status: v1.0 · verified end-to-end against a local stack on 2026-10-04 (see `docs/security/auth-design.md` for the design this exercises).

Walks an operator through: starting the stack, bootstrapping the first `SUPER_ADMIN`, the forced password change, TOTP enrolment, a real login, token refresh, and lost-authenticator recovery — all via `curl` against a local backend. Every secret used below is generated on the spot in your shell and **never written to a file inside the repository** (not even a gitignored one). If you paste commands from here, generate your own values; don't reuse anything that ever touched a terminal history you share.

## 0. Prerequisites

- Docker running locally.
- `openssl`, `curl`, `python3` (stdlib only — used to compute TOTP codes in place of a phone authenticator app; see step 5).
- A free local TCP port for Postgres. `infrastructure/docker-compose.yml` maps Postgres to host port `5432`; if you already have a Postgres instance on that port (common if you run other projects), either stop it or run a throwaway container on another port instead — see the Troubleshooting section.

## 1. Generate throwaway secrets

Every secret the backend needs is read from the environment (`docs/security/auth-design.md` §10) — none has a committed value. Generate fresh ones per session:

```bash
export POSTGRES_PASSWORD=$(openssl rand -base64 24)
export LADYGO_JWT_SECRET=$(openssl rand -base64 48)
export LADYGO_OTP_HASH_SECRET=$(openssl rand -base64 48)
export LADYGO_MFA_ENC_KEY=$(openssl rand -base64 24 | cut -c1-32)   # must be exactly 32 ASCII chars (AES-256)
export LADYGO_BOOTSTRAP_ADMIN_EMAIL=super.admin@ladygo.local
export LADYGO_BOOTSTRAP_ADMIN_PASSWORD=$(openssl rand -base64 18)
export LADYGO_BOOTSTRAP_ADMIN_PHONE=+919900011122   # required only to satisfy users.phone NOT NULL; never a staff login factor
```

These exports live only in your shell's process environment for this session. Keep this terminal open — every later step in this runbook assumes these variables are still exported in it.

## 2. Start infrastructure and the backend

```bash
cd infrastructure
docker compose up -d postgres redis   # minio is for driver documents, not needed for this runbook
```

If port `5432` is already taken by another local Postgres, run a throwaway one instead (adjust the port in the `SPRING_DATASOURCE_URL` below to match):

```bash
docker run -d --name ladygo-runbook-postgres \
  -e POSTGRES_DB=ladygo -e POSTGRES_USER=ladygo -e POSTGRES_PASSWORD="$POSTGRES_PASSWORD" \
  -p 127.0.0.1:55432:5432 postgis/postgis:16-3.4
```

Then, in the same shell (so it inherits the exports from step 1):

```bash
cd ../backend
export SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:55432/ladygo"   # or 5432 if you used docker compose
export SPRING_DATASOURCE_USERNAME=ladygo
export SPRING_DATASOURCE_PASSWORD="$POSTGRES_PASSWORD"
mvn spring-boot:run
```

Watch the startup log for two lines that confirm the bootstrap ran:

```
... Successfully applied 12 migrations to schema "public" ...
... AdminBootstrapRunner - operation=admin_bootstrap result=created email=super.admin@ladygo.local
```

If you instead see `result=skipped reason=no_super_admin_and_no_bootstrap_env_vars`, one of the three `LADYGO_BOOTSTRAP_ADMIN_*` variables isn't exported in the shell `mvn spring-boot:run` is running in — re-check step 1 and restart.

Confirm the API is reachable: `curl -s http://localhost:8080/api/v1/system/ping` should return `{"success":true,"data":{"status":"UP",...}}`.

## 3. First login: forced enrolment

```bash
curl -s -X POST http://localhost:8080/api/v1/auth/admin/login \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"$LADYGO_BOOTSTRAP_ADMIN_EMAIL\",\"password\":\"$LADYGO_BOOTSTRAP_ADMIN_PASSWORD\"}"
```

Response: `remainingSteps: ["PASSWORD_CHANGE", "MFA_ENROLL"]` and an `enrolmentToken` — a short-lived JWT good *only* for the three enrolment endpoints (`docs/security/auth-design.md` §3.2), not for anything else, and not yet for a real session.

## 4. Change the password

```bash
export ENROLMENT_TOKEN=<enrolmentToken from step 3>
export NEW_PASSWORD=$(openssl rand -base64 18)

curl -s -X POST http://localhost:8080/api/v1/auth/admin/password/change \
  -H "Authorization: Bearer $ENROLMENT_TOKEN" -H 'Content-Type: application/json' \
  -d "{\"currentPassword\":\"$LADYGO_BOOTSTRAP_ADMIN_PASSWORD\",\"newPassword\":\"$NEW_PASSWORD\"}"
```

Expect `{"success":true,"data":null,...}`. Try `POST /api/v1/auth/admin/mfa/enroll` again with this *same* `ENROLMENT_TOKEN` and you'll get `403 Forbidden` — that token's `pwdOk` claim was frozen at `false` when it was issued, before the password change, so it can never pass the MFA-enrol gate even though the password change it just performed succeeded. This is intentional (§3.5): log in again to get a token whose claims reflect the new state.

```bash
curl -s -X POST http://localhost:8080/api/v1/auth/admin/login \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"$LADYGO_BOOTSTRAP_ADMIN_EMAIL\",\"password\":\"$NEW_PASSWORD\"}"
```

This time `remainingSteps: ["MFA_ENROLL"]` only. Export this new `enrolmentToken` as `ENROLMENT_TOKEN` for the next step.

## 5. Enrol TOTP

```bash
curl -s -X POST http://localhost:8080/api/v1/auth/admin/mfa/enroll -H "Authorization: Bearer $ENROLMENT_TOKEN"
```

Response: a base32 `secret` and an `otpauth://` `provisioningUri`.

**Manual step for a real operator:** this is the one step in this whole runbook you cannot do by `curl` alone. In a browser or terminal that can render a QR code, turn `provisioningUri` into a QR code and scan it with an authenticator app (Google Authenticator, 1Password, Authy, ...) — or type the `secret` into the app manually if it supports that. The app then shows you a 6-digit code that changes every 30 seconds; use *that* code in the next step instead of computing one yourself.

For this runbook (no phone in the loop), a TOTP code was computed directly from the secret with Python's standard library, exactly re-implementing RFC 6238 the same way `backend/src/main/java/com/ladygo/security/crypto/TotpService.java` does:

```bash
CODE=$(python3 - <<'EOF'
import base64, hmac, hashlib, struct, time
secret = "PASTE_THE_SECRET_FROM_THE_RESPONSE_HERE"
key = base64.b32decode(secret)
step = int(time.time() // 30)
h = hmac.new(key, struct.pack(">Q", step), hashlib.sha1).digest()
offset = h[-1] & 0x0F
code = (struct.unpack(">I", h[offset:offset+4])[0] & 0x7FFFFFFF) % 1_000_000
print(f"{code:06d}")
EOF
)
echo "$CODE"
```

```bash
curl -s -X POST http://localhost:8080/api/v1/auth/admin/mfa/enroll/confirm \
  -H "Authorization: Bearer $ENROLMENT_TOKEN" -H 'Content-Type: application/json' \
  -d "{\"code\":\"$CODE\"}"
```

Expect `{"success":true,"data":null,...}`. Enrolment is done.

## 6. A real login

```bash
# Codes are one-time: the step used to confirm enrolment above cannot be replayed (section 3.4), so
# compute a fresh one. If barely any time passed, bump `step` by +1 rather than regenerating for `step`
# itself - the verifier accepts the step either side of "now", so +1 is still valid without waiting.
CODE=$(python3 - <<'EOF'
import base64, hmac, hashlib, struct, time
secret = "PASTE_THE_SAME_SECRET_HERE"
key = base64.b32decode(secret)
step = int(time.time() // 30) + 1
h = hmac.new(key, struct.pack(">Q", step), hashlib.sha1).digest()
offset = h[-1] & 0x0F
code = (struct.unpack(">I", h[offset:offset+4])[0] & 0x7FFFFFFF) % 1_000_000
print(f"{code:06d}")
EOF
)

curl -s -X POST http://localhost:8080/api/v1/auth/admin/login \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"$LADYGO_BOOTSTRAP_ADMIN_EMAIL\",\"password\":\"$NEW_PASSWORD\",\"totpCode\":\"$CODE\",\"deviceId\":\"runbook-test\"}"
```

Expect a real `accessToken` + `refreshToken` pair this time (`AdminLoginOutcome.Authenticated`, not `EnrolmentRequired`).

## 7. Refresh

```bash
export REFRESH_TOKEN=<refreshToken from step 6>
curl -s -X POST http://localhost:8080/api/v1/auth/refresh \
  -H 'Content-Type: application/json' -d "{\"refreshToken\":\"$REFRESH_TOKEN\"}"
```

You get a new pair back; the old refresh token is now revoked. Trying it again (re-running the exact same `curl` command) returns `401` — that's the reuse-detection path (`docs/security/auth-design.md` §6), not a bug. **Operational note:** there is no grace window — if a response is lost to a dropped connection, retrying with the same old token looks identical to theft and revokes the whole device's session, forcing re-login. Don't build client retry logic that blindly resends the same refresh token after an ambiguous failure (timeout, connection drop); treat that case as "re-authenticate," not "retry."

## 8. Lost-authenticator recovery

Recovery is **never self-service** (`docs/security/auth-design.md` §3.3) — it is always one `SUPER_ADMIN` acting on *another* staff member's account, audited. There is no Phase 9 admin-user-creation UI yet, so to demonstrate this with a second account, insert one directly (this is exactly the kind of row that UI will create later):

```bash
docker exec <your-postgres-container> psql -U ladygo -d ladygo -c "
INSERT INTO users (phone, email, role, password_hash, must_change_password, mfa_enabled, mfa_secret_enc)
VALUES ('+919900033344', 'other.admin@ladygo.local', 'ADMIN', 'placeholder-hash-not-a-real-login', false, true, 'placeholder-ciphertext-simulating-an-enrolled-secret')
RETURNING id;
"
```

Using the `SUPER_ADMIN` access token from step 6, reset that account's MFA:

```bash
export TARGET_ID=<id from the INSERT above>
curl -s -w '\nHTTP %{http_code}\n' -X POST "http://localhost:8080/api/v1/admin/users/$TARGET_ID/mfa/reset" \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

Expect `200`. Verify: `mfa_enabled` is now `false` and `mfa_secret_enc` is `NULL` for that row — the account drops back into the enrolment flow at the `MFA_ENROLL` step next time it logs in with its password, exactly like a brand-new account. An `audit_logs` row with `action = 'ADMIN_MFA_RESET'`, the acting `SUPER_ADMIN`'s id, and the target's id is written in the same transaction.

### If it's your *own* device that's lost, and you're the only SUPER_ADMIN

There is deliberately no API for this — one is a self-service MFA bypass, which defeats the point of requiring MFA at all. Recovery is an out-of-band operator action directly against the database, by someone who already has production DB access (the same trust boundary as whoever can read `LADYGO_DB_PASSWORD`):

```sql
UPDATE users SET mfa_enabled = false, mfa_secret_enc = NULL, mfa_pending_secret_enc = NULL
WHERE email = 'the-locked-out-admin@example.com';
```

Log this action outside the application (change ticket, shell history) the same way any direct production DB write would be logged. **Operational guidance:** provision at least two `SUPER_ADMIN` accounts in any real environment specifically so one can always recover the other through the API path above, instead of ever needing this.

## 9. Sign out

```bash
curl -s -X POST http://localhost:8080/api/v1/auth/logout \
  -H "Authorization: Bearer $ACCESS_TOKEN" -H 'Content-Type: application/json' \
  -d "{\"refreshToken\":\"$REFRESH_TOKEN\",\"logoutAll\":false}"
```

`"logoutAll": true` instead revokes every refresh token for that user, on every device.

## 10. Cleanup

```bash
# Stop the backend (Ctrl+C in its terminal, or):
pkill -f spring-boot:run

# Stop/remove whatever Postgres you started for this:
cd infrastructure && docker compose down -v        # if you used docker compose
docker rm -f ladygo-runbook-postgres                # if you used the throwaway container instead

# Nothing to clean up in the repo - no secret ever touched a file in it.
unset POSTGRES_PASSWORD LADYGO_JWT_SECRET LADYGO_OTP_HASH_SECRET LADYGO_MFA_ENC_KEY \
      LADYGO_BOOTSTRAP_ADMIN_EMAIL LADYGO_BOOTSTRAP_ADMIN_PASSWORD LADYGO_BOOTSTRAP_ADMIN_PHONE \
      NEW_PASSWORD ENROLMENT_TOKEN ACCESS_TOKEN REFRESH_TOKEN CODE TARGET_ID
```

## Troubleshooting

- **`bind: address already in use` on port 5432**: something else on your machine already runs Postgres on the default port (check with `lsof -nP -iTCP:5432 -sTCP:LISTEN`). Don't kill a process you don't recognise — it may belong to another project. Use the throwaway-container-on-another-port approach in step 2 instead.
- **`result=skipped reason=no_super_admin_and_no_bootstrap_env_vars`**: `mvn spring-boot:run` is running in a shell (or a shell session) where the three `LADYGO_BOOTSTRAP_ADMIN_*` variables aren't exported. Environment variables don't survive opening a new terminal tab — re-export them in the exact shell that runs the backend.
- **A staff login always `401`s even with the right password**: check `users.status` for that row — the account must be `ACTIVE` (`docs/security/auth-design.md` §14 fixed a gap where this wasn't checked at all; it now is, and a `SUSPENDED`/`DELETED` account is correctly rejected with `403`, not `401`).
- **`mfa/enroll` or `mfa/enroll/confirm` returns `403` right after a password change that just succeeded**: expected — see step 4. Log in again to get a token whose claims reflect the change.
- **TOTP code rejected**: your shell's clock and the backend's clock must agree to within about 30 seconds (both validity-window endpoints). If you're computing codes by hand per step 5/6, make sure you're using the *current* wall-clock time, not a stale `$(date)` captured earlier in the script.
