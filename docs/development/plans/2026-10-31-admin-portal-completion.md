# Admin portal completion plan (DRAFT, awaiting owner approval)

Date: 2026-10-31. Status: **nothing built yet.** Approve, or edit, before any code is written.

## 1. Goal and 60-day target

The admin portal (`frontend/admin`) is the founder's own monitoring tool. It must be safe and complete enough
that the closed pilot (5-10 drivers, 30-50 riders, per roadmap) can run with you watching it.

60-day success = pilot riders and drivers actually use Lady Go. The admin portal does not get users itself.
It protects that number in three ways:
- Drivers get approved fast (no pilot driver waits more than 24h for review).
- A rider SOS is acknowledged in minutes, not hours.
- A bad actor or stuck ride is dealt with the same day.

Measures (all read from the portal itself or the database):
- Median hours from "driver submitted documents" to "approved/rejected".
- Median minutes from SOS raised to acknowledged.
- Count of stuck rides and how long each stayed stuck.
- Weekly active riders and drivers (needs the Dashboard to show it; see step 9).

## 2. What exists today (checked in code)

Built screens: Dashboard, Login, driver verification queue and review, ride oversight and detail with flags,
audit log, pricing/config editor.

Four routes are still `Placeholder` in `frontend/admin/src/App.tsx`: **live-map, drivers, customers, safety**.

Backend admin endpoints that exist: driver documents/review/approve/reject, vehicles approve/reject,
eligibility reports, rides list/detail, ride flags review, audit logs, config, `POST /users/{id}/mfa/reset`.
**No backend admin endpoint exists yet for**: drivers list, customers list, suspend/activate, safety incidents
(the `safety_incidents` and `safety_incident_events` tables already exist, migration V10), live positions.

## 3. Scope

IN (this plan):
1. Step-up MFA on destructive actions (launch gate).
2. Admin login lockout hardening (launch gate).
3. Drivers screen: list, search, detail, suspend/activate.
4. Customers screen: search, detail, ride history, suspend/activate.
5. Safety screen: queue, acknowledge, notes, resolve, timeline.
6. Live map: online drivers, active rides, open safety incidents.
7. Production CSP `frame-ancestors` header (deploy-time).
8. Dashboard additions for the 60-day measures.
9. Admin user management screen (list admins, invite, change role, MFA reset) because step-up MFA doc says it shares the gap.
10. Admin password recovery (today there is no forgot-password path at all; see section 7): email reset link plus a break-glass server script.

OUT (not in this plan):
- Payments screen. The pilot is cash only (roadmap), so no transactions or refunds exist.
- Ride interventions: reassign driver, adjust fare, force-complete. Proposed for phase 2 unless you want them now.
- Historical location trails (needs a separate permission and audit design).
- Surge rules, reports.

## 4. Decisions I need from you (each blocks a step)

| # | Question | My recommendation |
|---|----------|-------------------|
| A | Live map: which map provider? Needs an API key and has a cost. | OpenStreetMap tiles with Leaflet. Free, no key. Fine for 10 drivers. |
| B | Is phase 7 live tracking backend ready? Roadmap says WebSocket/Redis infra built but live-tracking UI not started. | I check the driver location feed in step 6 and report. If missing, the live map shows last-known positions via polling. |
| C | SOS operating model (decision D9): who watches the safety queue, who calls police? | You (founder) for the pilot, daytime only, matches pilot rules. I build the queue and a clear "call 112" prompt, not automated dispatch. |
| D | Step-up MFA window: how long after a fresh TOTP before it asks again? | 5 minutes, one code per sensitive action batch. |
| E | Do the interventions (reassign, adjust fare, force-complete) go in this plan? | Defer. Pilot is 30-50 riders and you can handle by phone. |

## 5. Users and their problem

One user type at launch: you (SUPER_ADMIN), plus later 1-2 SUPPORT/ADMIN helpers. Problems: cannot find a
driver or rider quickly, cannot see or act on an SOS, cannot stop a bad actor, config edits are not re-verified.

## 6. UI/UX flow (per screen)

- **Drivers**: table (name, phone, status, last online, rating) with search by phone/name/ID and status filter.
  Click opens detail: documents (links to existing review), ride history, earnings, suspend/activate button.
  Suspend asks for a mandatory reason, then step-up MFA, then confirms.
- **Customers**: same pattern. Search by phone/name/ID. Detail: profile, ride history. Suspend/activate.
- **Safety**: queue sorted by severity then age. Open SOS rows are red. Row opens a timeline. Buttons:
  Acknowledge (timestamped), Add note, Resolve (mandatory outcome). Screen auto-refreshes every 15s and the
  nav shows a badge with the open count.
- **Live map**: map plus side list. Markers: online drivers, drivers on a ride, active rides, open incidents.
  Click shows the fields listed in `docs/product/admin-flows.md` section 3.
- **Mobile width**: tables collapse to cards (the last commit already made tables mobile-safe; reuse that pattern).

## 7. Data and API design

New backend endpoints (all under `/api/v1/admin`, every write audit-logged, every write needs a reason):

| Endpoint | Role | Notes |
|----------|------|-------|
| `GET /drivers`, `GET /drivers/{id}` | SUPPORT+ | Search + paging. |
| `POST /drivers/{id}/suspend`, `/activate` | ADMIN+ | Reason + step-up. Suspend forces driver offline and blocks offers. |
| `GET /customers`, `GET /customers/{id}` | SUPPORT+ | Search + paging. |
| `POST /customers/{id}/suspend`, `/activate` | ADMIN+ | Reason + step-up. Booking must refuse suspended customers. |
| `GET /safety/incidents`, `GET /safety/incidents/{id}` | SUPPORT+ | Includes timeline from `safety_incident_events`. |
| `POST /safety/incidents/{id}/acknowledge`, `/notes`, `/resolve` | SUPPORT+ | Append-only events. |
| `GET /live-map` | SUPPORT+ | Live positions only, no history. |
| `POST /auth/admin/step-up` | any admin | Takes a TOTP code, returns a short-lived step-up proof. |
| `POST /auth/admin/password/forgot`, `/password/reset` | public (rate-limited) | Email link, TOTP required on reset. |
| `GET/POST /users` (admin users) | SUPER_ADMIN | List, invite, change role. Step-up required. |

Step-up design: server stores "last TOTP verified at" on the session. Destructive endpoints (config PUT,
suspend/activate, admin user changes, MFA reset) return `403 STEP_UP_REQUIRED` if older than the window.
The frontend catches that code, shows a 6-digit prompt, calls step-up, then retries the action once.
Server-side enforcement is the real control; the UI prompt is convenience.

Password recovery (new): the backend has only `/password/change`, which needs the current password, so a
forgotten password is unrecoverable without a database edit.
- Email reset link: `POST /auth/admin/password/forgot` always answers the same message whether or not the email
  exists (no account enumeration). It emails a single-use link with a random token, stored hashed, expiring in
  30 minutes. `POST /auth/admin/password/reset` takes token + new password + a current TOTP code (if MFA is
  enrolled), enforces the password policy, revokes all refresh tokens, and writes an audit entry. Rate-limited per
  email and per IP. A "Forgot password?" link on the login screen and a reset page in the portal.
- Break-glass script: `scripts/ops/admin-reset-link.sh` (run on the server by you) issues a one-time reset link directly
  in the database and prints it, plus an audit entry. It never handles a password or hash. (Built; replaced the earlier
  "set a password" idea.) Documented in the runbook, with a rule that
  at least two SUPER_ADMIN accounts exist once helpers are added.

Lockout hardening: replace the hard per-(email, IP) lockout with progressive delay (1s, 2s, 4s ... capped),
log failures, alert on N failures in M minutes, and add a SUPER_ADMIN unlock endpoint.

Database: migrations for suspend flags/reason on drivers and customers if not already present (to be
checked in step 1), and a step-up timestamp column or session field. Migrations are additive only.

## 8. Security and compliance

- Server enforces role and step-up on every endpoint. UI hiding is never the control.
- Reasons mandatory and stored in the audit log for every write.
- Document/selfie access stays short-lived signed URLs with access logging (already in design).
- Live map shows current position only. Location history stays out.
- Run `/security-review` and the adversarial-review pass before merge (money-free, but safety and PII critical).
- **High-risk items flagged:** (a) suspend/activate changes who can ride; (b) lockout rewrite can lock you out of
  your own admin, so keep a documented break-glass path; (c) step-up must not be bypassable by omitting a header;
  (d) password reset is an account-takeover path: token hashed, single-use, short expiry, same reply for unknown emails, TOTP still required; (e) any migration touching `drivers`/`customers` runs on staging first; (f) no unbounded UPDATE/DELETE anywhere.

## 9. Build steps (small slices, each ends with a real-user run)

Order puts the launch gates first because they block exposure.

| Step | Work | Model | Why |
|------|------|-------|-----|
| 1 | Read-only recon: check suspend columns, driver location feed, existing admin auth/session code | Haiku 5.5 | Mechanical reading. |
| 2 | Design note for step-up + lockout (short, approved by you) | Opus 5.5 | Security architecture. |
| 3 | Step-up MFA backend + frontend prompt, wired to ConfigScreen | Sonnet 5.5 | Feature work, tests first. |
| 4 | Lockout hardening | Sonnet 5.5 | Feature work. |
| 4b | Password recovery: forgot/reset endpoints, login link, reset page, break-glass script (**built**) | Sonnet 5.5 | Security-critical, tests first. |
| 5 | Safety endpoints + Safety screen | Sonnet 5.5 | Highest pilot value. |
| 6 | Drivers + Customers endpoints and screens, suspend/activate | Sonnet 5.5 | Two independent slices, can run in parallel sessions. |
| 7 | Live map | Sonnet 5.5 | Depends on decisions A and B. |
| 8 | Admin user management screen | Sonnet 5.5 | Reuses step-up. |
| 9 | Dashboard measures | Sonnet 5.5 | Small. |
| 10 | CSP header config + docs | Sonnet 5.5 | Deploy-time. |
| 11 | Fresh-context adversarial review, `/code-review`, `/security-review` | Opus 5.5 | Brain role. |

After every step: lint, type-check, tests, then I run the real app in the browser (happy path, wrong inputs,
mobile width) and report what a user sees. A step is done only when that passes.

## 10. Test steps

- Backend: failing test first for each endpoint (role matrix, missing reason, step-up expired, suspended rider cannot book).
- Frontend: component tests per screen.
- End to end (Playwright): login with TOTP, approve a driver, suspend and re-activate a customer, raise an SOS
  and acknowledge then resolve it, edit config with expired step-up and see the prompt.
- Password recovery tests: expired token, reused token, unknown email gives identical reply, wrong TOTP, rate limit, old sessions revoked.
- Manual: mobile width (375px), wrong TOTP, double click on destructive buttons.

## 11. Deploy and rollback

Staging first. Deploy order: backend migrations and endpoints, then admin web. All new endpoints are additive,
so rolling the admin web back is safe. Migrations are additive; rollback = redeploy previous backend (new columns
unused). Lockout change is the one risky deploy: keep the old behaviour behind a flag for one release.

## 12. Launch and marketing (60-day lens)

- Pilot ops runbook (one page): daily check order (safety queue, driver queue, stuck rides), who to call.
- Target: driver review under 24h, SOS acknowledge under 5 minutes. Both visible on the Dashboard.
- Marketing is not part of this admin work, but approval speed and safety response feed driver and rider trust.
  Weekly adoption report: suggest `/schedule` an agent for a weekly summary of the measures in section 1.

## 13. Web Admin tickets (paste and run, staging first)

Claude cannot execute these. Lifecycle for each: Ready for test, In testing/UAT, Test Failed, Ready To Deploy.

**T1. Set admin step-up window config.** Env: staging.
- Why: step-up MFA needs a window value (decision D).
- Steps: after step 3 ships to staging, open Admin > Pricing/Config, add `admin.stepup.window.minutes` = 5, reason "step-up MFA rollout".
- Accept: value saved, audit-log entry exists. Owner: you.

**T2. Create a SUPPORT test admin on staging.** Env: staging.
- Why: verify the role matrix as a non-super admin.
- Steps: once step 8 ships, Admin > Admin users > Invite, role SUPPORT, enrol TOTP.
- Accept: this user can open Safety and Customers, cannot suspend or edit config. Owner: you.

**T3. Configure `frame-ancestors` at the real web host.** Env: staging, then production.
- Why: cannot be a `<meta>` tag; roadmap launch gate.
- Steps: at the reverse proxy/CDN serving `frontend/admin` add header `Content-Security-Policy: frame-ancestors 'none'` (merge with the existing CSP). Ticket text will name the exact host once the deploy target is confirmed (see `2026-10-25-website-auto-deploy.md`).
- Accept: `curl -I <admin url>` shows the header. Owner: you.

**T4. Run migrations on staging and take a DB snapshot first.** Env: staging.
- Why: new columns on `drivers` and `customers`.
- Steps: snapshot, deploy backend, confirm app boots (Liquibase/Flyway log clean).
- Accept: health endpoint green, rows unchanged. Owner: you.

**T6. Set up Resend and confirm email sending works on staging.** Env: staging.
- Why: password reset link needs the email provider. Decision: Resend, sent over SMTP (already built, see `docs/development/email-otp-setup.md`). The SMTP sender now turns on from `LADYGO_EMAIL_PROVIDER=smtp` alone, so recovery emails work while driver codes still go by SMS.
- Steps: (1) In Resend add the domain `ladygoride.com`, add the DNS records it lists, wait for "Verified". (2) Create an API key with sending access only. (3) Run `scripts/ops/set-smtp-secret.sh` (hidden prompt; it writes `LADYGO_EMAIL_PROVIDER=smtp`, host `smtp.resend.com`, port 587, username `resend`, and the key). (4) Make sure `LADYGO_EMAIL_FROM` is `Lady Go <no-reply@ladygoride.com>`. (5) Restart the backend, then use Forgot password with your own admin email.
- Accept: email arrives within 2 minutes, link works once, second use fails. Owner: you.

**T7. Put the break-glass script on the server.** Env: staging, then production.
- Why: last-resort recovery if email is down or you lose access.
- Steps: copy `scripts/ops/admin-reset-link.sh` to the server, run it on staging for a throwaway admin with `PSQL="psql ..."` set if needed, open the printed link, set a password.
- Accept: the link works once, the password changes, `ADMIN_PASSWORD_RESET_LINK_ISSUED` and `ADMIN_PASSWORD_RESET` audit entries exist. Owner: you.

**T8. Set `LADYGO_ADMIN_WEB_BASE_URL` on staging and production.** Env: staging, then production.
- Why: the reset email needs the admin web address. Blank (the default there) switches the forgot-password email off; it
  logs a warning at startup but does not fail the deploy.
- Steps: set `LADYGO_ADMIN_WEB_BASE_URL=https://<admin web address>` in the backend environment, restart, confirm no
  `admin_password_reset_disabled` warning in the log.
- Accept: Forgot password on the admin login sends a link that opens the reset page. Needs T6 first. Owner: you.

**T5. Define the SOS procedure (decision C).** Env: n/a.
- Why: launch gate D9.
- Steps: write who watches the queue, hours, who calls 112.
- Accept: one page in `docs/product/`. Owner: you.

## 14. Claude features I suggest using

- `brainstorming` skill now, only if you want to revisit scope before approving.
- Parallel sessions for step 5 (Safety) and step 6 (Drivers/Customers): independent files.
- `test-driven-development` in every build step, `verification-before-completion` before saying any step is done.
- `/code-review` and `/security-review` before merge, `requesting-code-review` as the final gate.
- `/schedule` for a weekly admin and adoption summary during the 60 days.
