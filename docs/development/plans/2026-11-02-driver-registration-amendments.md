# Plan: driver registration amendments (password, loader, congratulations, FAQ/Terms/Contact)

Status: **DRAFT, waiting for the owner's approval. No code has been written.**
Date: 2026-11-02. Branch: `claude/driver-registration-amendments-e820fe`, one PR into `develop`. Nothing goes to `main`
(merging to `main` auto-deploys production).

How to read this: every term is explained the first time it appears. Section 12 lists the questions only the owner can
answer; sections 3 to 5 give my recommendation for each one so you can simply say "yes to all" or change a line.

---

## 1. Goal and the 60-day number

**Goal.** Make it easy and safe for a new captain to finish registering and to come back later.

**What success means (your rule): how many people use it in the first 60 days.** For this work the "people" are
captains. I will measure four things, all from data the product already stores (no new tracking tools):

| # | Measure | Why it matters |
|---|---|---|
| 1 | Captains who verified the code, then **set a password** | Shows the new step does not scare people off |
| 2 | Captains who reach "all steps complete" (the congratulations dialog) | The real finish line of registration |
| 3 | Captains who **signed in again by password** within 7 days of registering | Shows they come back without needing an emailed code |
| 4 | Support emails about "cannot sign in" or "forgot password" | Should fall, not rise |

**The target number is yours to set** (see question Q1). The pilot is 30 to 50 riders, so I suggest you pick a captain
count (for example "N captains fully registered and approved by day 60") and I will turn measures 1 to 3 into one saved
database query you can run any time (ticket W-5).

## 2. Users and their problem

- **Captain (driver) on a phone, two-wheeler, in the Kashmir pilot.** Today they sign in with a phone number plus a
  one-time code (OTP) emailed to them. That is slow (wait for email), fragile (email can be late or in spam), and the
  Android driver app **cannot sign in at all** while codes go by email, because it only sends a phone number.
- They have no password, so there is no quick way back in.
- Registration ends silently: when all steps are done the screen just shows a Back button. The captain does not know
  they are finished or what happens next.
- The FAQ, Terms and Contact pages are empty placeholders ("We are setting up support").

## 3. Scope

**In scope (the owner's four changes):**
1. Set a password right after the code screen; the **same** password signs in to the website and the Android driver app.
2. Loader after "Send code" until the code-entry screen shows.
3. Finished registration: no Back button, a congratulations dialog, support address `supportladygo@gmail.com`.
   (You typed "suuprtladygo@gmail.com"; the sending account is "supportladygo" so I use `supportladygo@gmail.com`.
   I will say in the PR that I corrected an apparent typo. Please confirm in Q2.)
4. Real FAQ, Terms of Service and Contact Us pages.

**Out of scope** (and why): customers (see 4g); changing how the OTP itself works; social login; passkeys; SMS; moving
the admin portal; the review-time promise (not decided); fares/commission text (unknown).

## 4. The design questions, each with my recommendation

### 4a. What identifies a driver at password sign-in? **Recommend: phone OR email, either one.**
Today the account is identified by **phone** (`users.phone`), and the **email is attached the first time the code is
verified**. The Android app and the website sign-in already ask for the phone, so phone must work. Some captains will
remember the email better. So the sign-in box accepts either; the server finds the one account and checks the
password. Wrong identifier, wrong password, no password set, and "no such account" all give the **same** reply
("Phone/email or password is not correct"). Suspended accounts are told only after the correct password is given.

### 4b. Forgot password and reset. **Recommend: reuse the admin recovery machinery, delivered as a link to the website.**
- Captain taps "Forgot password" and enters phone or email. The server **always answers the same words** ("If that
  account exists we have emailed a link"), takes the same time, whether or not the account exists (the admin version
  already does the work on a background worker to keep timing equal; I reuse that approach).
- The email contains a one-time link: 32 random bytes, only its SHA-256 fingerprint is stored, single use, expires in
  30 minutes, token in the URL `#fragment` so it never reaches logs. This is exactly `AdminPasswordResetService`.
  I will generalise it (a shared token helper plus a driver wrapper) instead of copying it. The existing table
  `admin_password_resets` is keyed by `user_id`; the build step checks whether it is usable for drivers as is. If it has
  anything admin-only, a new table goes in the new migration.
- The link opens a **website** page (`/captain/reset-password`) in every case, including captains who use the Android
  app; the app's "Forgot password" button opens that page in the browser. (An in-app deep link is extra work and cannot be
  tested without a device, so it is a later improvement.)
- A successful reset: sets the new password, **signs out every session** (all refresh tokens revoked), invalidates other
  open links, writes an audit entry, and emails "your password was changed".
- **It does not weaken OTP.** OTP sign-in stays exactly as it is and does not depend on the password. Resetting a
  password needs the emailed link, which proves access to the **email bound to the account**, the same proof OTP uses.
  The reset never changes the phone or the email, and never creates a login that OTP did not already allow.

### 4c. Password rules. **Recommend: length and a common-password check only.**
- Minimum **10** characters (admins use 12; 10 is kinder to a thumb keyboard and still strong when combined with the
  checks below; Q3 lets you pick 12), maximum 128.
- Reject passwords in a bundled list of the most common passwords, and ones equal to the captain's own phone number or
  email name. The list is a plain text file in the repo; I will use a public-domain "top passwords" list and record its
  source.
- **No** "must contain a capital, a number and a symbol" rules; these push people to `Password1!`. Spaces and any
  characters are allowed; pasting is allowed (password managers).
- The rules are enforced on the **server**; the website only shows the same rules as a hint.

### 4d. Storage. **Recommend: the same `PasswordEncoder` bean admins use, into the existing `users.password_hash`.**
The column already exists (admins use it; drivers have it empty). No second hashing method. Passwords are never logged,
never returned in any response, and never put in an audit entry.

### 4e. Guessing limits. **Recommend: reuse both existing patterns, and count rejected attempts.**
- **Per account (progressive wait):** reuse `AdminLoginThrottle` with a key of `driver:<phone or email>`: each wrong
  attempt doubles the wait (1s, 2s, 4s ... up to 15 minutes). Unknown identifiers are tracked the same as real ones so
  the reply and timing never reveal who exists. Success clears it.
- **Per IP:** reuse `RateLimiter`: 20 password attempts per 15 minutes per IP, plus the existing limits for reset
  requests (3 per hour per identifier, 12 per IP).
- **Rejected attempts must still be counted.** The sign-in method follows `AdminAuthService.login`: its transaction is
  marked `noRollbackFor` the "wrong password / forbidden / too many" exceptions, otherwise the failure counter would be
  rolled back together with the rejected request and an attacker could guess forever. Tests (4h-style, see section 8)
  run **committed, not wrapped in a rolled-back test transaction**, because a class-level `@Transactional` test hides
  exactly this bug.
- A real unknown account still pays the cost of one dummy password hash so timing does not reveal existence.
- **Trade-off, honestly:** progressive waiting lets an attacker who knows a captain's phone make that captain wait up to
  15 minutes (annoyance, not a break-in). Mitigations: OTP sign-in is never affected by the password wait, and the cap is
  15 minutes. Flagged as risk R3.

### 4f. Existing drivers with no password. **Recommend: OTP keeps working; they are asked to set a password.**
After a successful OTP sign-in on the website, if the account has no password, the set-password screen is shown (with
"Not now" for existing captains; **required** during a new sign-up). Nothing stops them using OTP. The Android app
never needs to ask: captains register on the website, where this prompt lives.

### 4g. Customers. **Recommend: out of scope.**
The request is about driver registration. The rider app has no sign-up website and its own plan (and customer accounts
are a bigger abuse surface). The new endpoints are written for the DRIVER role only and reject customers. Customers keep
phone + OTP.

### 4h. Android driver app. **Recommend: add password sign-in; keep OTP screen as fallback.**
What has to change in `mobile/android-driver`:
1. After the OpenAPI client is regenerated, `AuthRepository` gets `loginWithPassword(identifier, password)` calling the new
   endpoint and storing the token pair exactly like OTP login does (`TokenStore`, `SessionManager`).
2. The `login` screen/ViewModel gets: identifier field, password field (show/hide), "Sign in", "Forgot password" (opens the
   website reset page in the browser), and "Use a code instead" for OTP.
3. Error text mapping (wrong password, wait, suspended, network).
4. Unit tests for the repository and ViewModel.

**What I can and cannot verify here:** I can build the app, run its unit tests, regenerate the API client and run the
`checkOpenApiClientDrift` task. I **can try** running it on an Android emulator only if one is installed on this Mac
(I will check; the `step3-emulator-run` note shows one was used before). I **cannot** test on a real phone, with real
Kashmir network conditions, with a real Play Store build, or with a real emailed reset link on a phone. The owner needs
to do one real-device sign-in (ticket W-4). I would not call the Android part "done" until that is ticked.

### 4i. Database migration. **Expected: one small additive migration, `V42`.**
V38 to V41 are taken by admin work (PR 92 uses V40; others reserved). The newest on `origin/develop` is V37. I will
re-check `origin/develop` and all open PRs right before writing the file and take the next free number. Content (additive
only, nothing dropped or rewritten): `users.password_updated_at timestamptz NULL` (lets us show "password last changed"
and ask old accounts to set one), and only if 4b shows the reset table cannot serve drivers, a driver reset table.
If that column turns out to be unnecessary, there is no migration and nothing to number.

### 4j. Takeover risk of setting a password right after OTP. **Recommend: allow, with three guards.**
Background: in email-OTP mode the code proves access to an **email**, not that you own the **phone**
(`docs/development/email-otp-setup.md`). A stranger who knows a phone number could register it with their own email
first ("first claim"). The 409 `OTP_EMAIL_MISMATCH` rule stops anyone else from claiming a number once it is bound.
**Does a password make this worse? Only slightly, and not through a new door.** Whoever passed the OTP already owns the
account and could already sign in by OTP whenever they like. A password adds a second way in for the same person, plus it
removes the need to receive an email. It does not let a third party in. Guards:
1. Setting a **first** password needs a **fresh** OTP sign-in (access token issued in the last 10 minutes), so a
   stolen or old token cannot plant a password. **Changing** an existing password needs the current one.
2. Password reset only goes to the bound email (4b); it can never rebind phone or email.
3. Set, change and reset are written to the audit log, all sessions are revoked on change/reset, and the captain gets a
   "your password was changed" email.
The real backstop for first-claim is unchanged: admin document review, and calling the number before approving
(already in the email-OTP doc). The plan does not fix first-claim; it is listed as risk R2 so the decision stays visible.

## 5. UX flow (what a captain sees)

1. `/captain` sign-in, step 1: phone + email, **Send code**. The button disables and shows a spinner and the words
   "Sending your code..." (change 2) until the code screen is on screen. If sending fails, the spinner stops and the error shows.
2. Step 2: enter the 6-digit code.
3. **New:** "Create your password" (new sign-ups: required; existing captains: "Not now" allowed). Shows the rules in
   plain words, a show/hide toggle, and one confirm field.
4. Registration steps (vehicle with RC photo, selfie, declaration; licence optional).
5. **Changed:** when every required step is done the Back button disappears and a "Congratulations, captain" dialog opens:
   encouraging message, "your application is with our team and we will contact you", support
   `supportladygo@gmail.com` (mailto link), a Close button; Escape also closes. **No review time is promised** (none is
   recorded; Q4).
6. Next visit: `/captain` shows **Sign in with password** (phone or email + password) with "Forgot password?" and
   "Use a code instead".

## 6. Data and API design

New endpoints, all under `/api/v1/auth/driver/` (names final in the build):

| Endpoint | Who | Reply |
|---|---|---|
| `POST password/set` | signed-in DRIVER; fresh token if no password yet; current password if changing | 204, nothing else |
| `POST password/login` | anyone | tokens (same shape as OTP verify) or one generic 401; 429 when waiting |
| `POST password/forgot` | anyone | always the same 200 text |
| `POST password/reset` | holder of emailed token | 204, or generic 401 |

- Request bodies are length-limited; unknown fields ignored; passwords only in the request **body**, never in the URL.
- Responses and logs never contain passwords, tokens, hashes or the full email/phone (logs use the last 4 digits of the
  phone or a short hash, like the existing code).
- Audit entries (`audit_log`): `DRIVER_PASSWORD_SET`, `DRIVER_PASSWORD_CHANGED`, `DRIVER_PASSWORD_LOGIN_FAILED`(throttled,
  not per attempt, to avoid log flooding), `DRIVER_PASSWORD_RESET`. The existing `ErrorCode` set is reused; the
  429 / 401 / 409 shapes stay as they are.
- A DB-config switch `auth.driver_password.enabled` (default on after approval) lets us turn the endpoints off without a
  redeploy if something goes wrong.
- OpenAPI contract regenerated: `docs/api/openapi.json`, both frontends' `api-types.ts`, and both Android clients (done
  last, after rebasing on the latest `origin/develop`, never hand-merged, because PRs 92 to 94 touch the same files).
- `docs/security/auth-design.md` is updated with the new flow.

## 7. Content changes (2 to 4)

- **Change 2 and 3** live in `frontend/website/src/registration/pages/Login.tsx` and `Dashboard.tsx` (plus tests and
  the Playwright file `e2e/driver-registration-flow.spec.ts`). Dialog is accessible: `role="dialog"`, labelled, focus
  trapped, Escape closes, focus returns, a clear Close button.
- **Change 4** pages already exist as stubs: `frontend/website/src/pages/{Faq,Terms,Contact,Privacy}.tsx`.
  Findings: `/api/v1/support/contact` (`SupportContactController`) requires a signed-in user, and the public website does
  not read it, so Contact Us will show `supportladygo@gmail.com` as plain content in the page. (I will not make that
  endpoint public just for this.) Wording matches the `driver-v2` captain declaration in
  `docs/legal/captain-self-declaration-driver-v2.md`: Lady Go is a platform, not an employer, does not own or inspect
  vehicles or documents; two-wheelers only; cash only during the pilot; Kashmir pilot; document/verification process;
  suspension; safety, SOS and call 112; privacy and data collected (documents, selfie, phone, email). I will **not**
  invent: company legal name, address, GSTIN, fares, commission, working hours, response times, refund rules,
  retention periods. Any such gap is removed from the text and listed as a `TODO(owner)` in the PR description, never on
  the live page. The PR will say prominently that **you must review the legal wording before it goes live**, with a list
  of every claim that needs confirming. (Privacy: "how long data is kept" cannot be stated without a decision from you.
  Q5.)

## 8. Build order and test steps

Per your rule 3, after each step I run it as a user and report what is seen, not just unit tests.

| Step | What | Model | Tests first |
|---|---|---|---|
| 0 | This plan, committed, **stop for approval** | Opus (this) | - |
| 1 | Change 2 loader | Sonnet | Login component test (button disabled, `aria-busy`, spinner+text, error clears it) |
| 2 | Change 3 dialog + remove Back | Sonnet | Dashboard tests (focus trap, Escape, mailto, Back absent) + Playwright update |
| 3 | Change 4 pages | Sonnet | page render tests; no `TODO` text on the live page (test greps for it) |
| 4 | Change 1 backend | Sonnet | integration tests: set/login/forgot/reset, committed (non-transactional) tests for counters, lockout, reuse of a reset link, expired link, suspended driver, customer rejected, same reply for unknown account, no secret in logs/responses |
| 5 | Change 1 website screens | Sonnet | component tests + Playwright |
| 6 | Change 1 Android | Sonnet | repository/ViewModel unit tests, build, drift check, emulator if available |
| 7 | Regenerate contracts, rebase `origin/develop` | Haiku (mechanical) | `openapi:check` both frontends, drift check both Android apps |
| 8 | Review | Opus | `/security-review` on the diff (authentication change), full backend suite, vitest, typecheck |
| 9 | Real browser check at desktop and 375px wide, Playwright workflows via `gh workflow run driver-registration-e2e.yml` and `admin-web-e2e.yml` on the branch | - | report what a user sees |

Superpowers skills I will use: `test-driven-development` for steps 1 to 6, `verification-before-completion` before
saying done, `requesting-code-review` + `/security-review` before the PR.

## 9. Deploy and rollback

- PR into `develop` only, **not merged by me**. Staging first (after you merge to `develop`, the staging flow applies the
  migration and the new build).
- Order on release: backend first (additive migration + endpoints, nothing reads them yet), then website, then the
  Android build. Old Android builds keep working (OTP unchanged).
- **Rollback:** (1) flip `auth.driver_password.enabled` to off (instant, no deploy); (2) redeploy the previous
  build. The migration only **adds** things, so the old code keeps running on the new schema; nothing is dropped.
- Merging to `main` later auto-deploys production. That step is yours and is not part of this PR.

## 10. Security and compliance, and the high-risk list

| ID | Risk | Level | Handling |
|---|---|---|---|
| R1 | New **authentication** path (password login/reset) | HIGH | Reuses admin patterns, `/security-review`, committed tests, kill switch |
| R2 | First-claim takeover (phone not verified in email mode) | HIGH (existing) | Not worsened; guards in 4j; admin review + phone call remain the backstop |
| R3 | Lockout used to annoy a known captain | MEDIUM | 15 min cap; OTP unaffected |
| R4 | Reset link via email only; email can be compromised | MEDIUM | Single-use, 30 min, sessions revoked, "changed" notice email |
| R5 | Password in request body/logs | MEDIUM | Never logged/returned; tests assert it |
| R6 | New DB migration on shared schema | LOW | Additive only, next free number re-checked |
| R7 | Legal pages going live with unreviewed wording | HIGH | Not live-claimed; PR lists every claim; owner reviews |
| R8 | Android part unverified on a real device | MEDIUM | Stated honestly; ticket W-4 |
| R9 | Money movement, unbounded DELETE/UPDATE, hardcoded credentials | NONE | None planned; any bulk statement carries a WHERE; no secrets in code |

Not in this work: payments, production data changes.

## 11. Web Admin tickets (drafted for you to paste; Claude cannot execute these)

Lifecycle for each: Ready for test, In testing/UAT, Test Failed, Ready To Deploy. **Environment: staging first.**

**W-1. Set the website address used in reset emails (staging, then prod)**
- Why: the reset email needs the public captain-site address to build its link. No value is invented by me.
- Steps: (1) Decide the address, for example `https://staging.<your-site>`; (2) on the staging server, over the
  approved SSM session, add `LADYGO_WEBSITE_BASE_URL=<address>` to `/opt/ladygo/.env` (do not paste any secret into
  chat); (3) restart the backend service; (4) check the log line `admin_password_reset_disabled` is absent.
  (The exact setting name is confirmed in the build; I will update this ticket.)
- Acceptance: the "forgot password" email received on a test captain account opens the reset page.
- Owner: Wamiq. Env: staging, then prod.

**W-2. Confirm the support mailbox**
- Why: the congratulations dialog and Contact page promise replies from `supportladygo@gmail.com`.
- Steps: (1) sign in to that Gmail account; (2) send it a test message from another address; (3) confirm it arrives;
  (4) confirm who will read it daily during the pilot.
- Acceptance: test message received; a named person is responsible.
- Owner: Wamiq. Env: n/a (live mailbox).

**W-3. Confirm email sending works (Resend)**
- Why: password reset and "password changed" emails go through the same sender as OTP codes.
- Steps: (1) in staging, request a captain code and one reset email; (2) check inbox and spam; (3) confirm sender shows
  as `no-reply@ladygoride.com`; (4) check the Resend dashboard daily-limit (100/day on the free plan) is enough.
- Acceptance: both emails arrive in under 2 minutes; limit noted.
- Owner: Wamiq. Env: staging.

**W-4. Real-phone check of the Android driver app**
- Why: I cannot verify on a real device.
- Steps: install the staging driver build on an Android phone; sign in with a captain's phone + password; log out; try a
  wrong password 3 times and note the wait; use "Forgot password", open the emailed link, set a new password, sign in again.
- Acceptance: every step works on the phone; no crash; messages are understandable.
- Owner: Wamiq (or a trusted tester). Env: staging.

**W-5. Save the 60-day adoption query**
- Why: measures in section 1.
- Steps: I will provide the read-only SQL; run it against the staging then prod reporting access you normally use.
- Acceptance: it returns the four counts.
- Owner: Wamiq.

**W-6. Review legal wording before live**
- Why: Terms/Privacy/FAQ are plain-language drafts, not legal advice.
- Steps: read the PR's "claims to confirm" list; edit or approve each; ideally a lawyer reviews Terms and Privacy.
- Acceptance: written approval in the PR. Pages are not announced publicly before this.
- Owner: Wamiq (+ legal). Env: staging first.

## 12. Questions only the owner can answer

- **Q1.** The 60-day target number (captains fully registered / approved)?
- **Q2.** Confirm the address is `supportladygo@gmail.com` (not "suuprtladygo").
- **Q3.** Password minimum length: 10 (my pick) or 12 (same as admins)?
- **Q4.** Do you have a target for how fast applications are reviewed? If not, the dialog promises none.
- **Q5.** How long should Lady Go keep registration documents and the selfie (and after a rejection)? Until you say, the
  Privacy page will not state a period.
- **Q6.** OK that existing captains can skip setting a password with "Not now" (new sign-ups cannot)?
- **Q7.** OK that the reset link always opens the website, also for app users (4b)?
- **Q8.** Company legal name, registered address, GSTIN, fares/commission, hours, response times, refund rules: if you want
  any in the Terms/Contact pages, send them; otherwise they stay out.
