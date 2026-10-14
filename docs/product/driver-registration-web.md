# Driver registration website — design

Status: proceeding directly to build per instruction (no approval gate). Short design, then build.

## 0. Scope

A public, mobile-first website (new app under `frontend/driver-registration`) letting a prospective
driver complete Phase 5 onboarding end-to-end: OTP login, profile, vehicle, every required document,
selfie, self-declaration, and a status page. Uses only the existing `/api/v1/drivers/me/*` and auth
endpoints — no new business rules invented. Test data only; no real-data collection is enabled in any
config this build ships (see §4).

## 1. Pages and flow

1. **Login** — phone number → `POST /auth/otp/request` (`intendedRole: "DRIVER"`) → OTP code →
   `POST /auth/otp/verify` → access token (memory only) + refresh cookie, same pattern as admin-web's
   `auth.ts`.
2. **City** — `POST /drivers/me` (bootstrap, idempotent) if no profile yet.
3. **Profile/status** — `GET /drivers/me`, `GET /drivers/me/vehicle`, `GET /drivers/me/documents`,
   `GET /drivers/me/declaration` (new, §2) in one dashboard: overall `verificationStatus`
   (`NOT_SUBMITTED`/`PENDING_REVIEW`/`APPROVED`/`REJECTED`/`SUSPENDED`), each document's own
   `status`/`reviewNote`/`reviewedAt`, vehicle `verificationStatus`, declaration acceptance. A
   rejected document can be resubmitted (re-`POST`s the same `type`) and goes back into the review
   queue.
4. **Vehicle** — form → `PUT /drivers/me/vehicle` (registration number, type, make/model/colour,
   manufacture year).
5. **Documents** — one upload step per `DocumentType` the design calls for: `LICENSE` (photo + number +
   expiry), `VEHICLE_REGISTRATION` (RC photo), `VEHICLE_PHOTO` (plate visible), `INSURANCE` (photo +
   expiry), `FITNESS_CERTIFICATE`, `POLLUTION_CERTIFICATE`. Each a client-side type/size check + preview,
   then `POST /drivers/me/documents` (multipart, §2 extends this with optional `number`/`expiresAt`).
6. **Selfie** — camera capture (`getUserMedia`) with a file-input fallback → `POST /drivers/me/selfie`.
7. **Declaration** — show the current version's text (`GET /drivers/me/declaration`, §2) → accept →
   `POST /drivers/me/declaration`.
8. **Status** (also the landing page on return visits) — same data as step 3; shows reviewer notes as
   plain text, never rendered as markup.

Resumable: every step's own already-submitted state comes back from the API itself (not local storage)
on reload - `getProfile`/`getVehicle`/`listDocuments`/the new declaration-status call are the source of
truth for "what's left," so a refresh mid-flow re-derives progress from the server. The only thing kept
client-side is which step the UI is currently showing and in-progress form field values (never a
document's image bytes) - see §3's localStorage note.

## 2. API gaps found (fixed below, not invented around)

Checked against the *approved* Phase 5 design (`docs/api/driver-onboarding.md` §0: "driving licence
(photo + number + expiry) ... insurance photo + expiry") versus what actually shipped:

1. **No way to submit a document's number or expiry date at all.** `DriverDocument.expiresAt` exists
   as a column and `AdminVerificationService` already checks it for `INSURANCE`, but nothing has ever
   set it — `uploadDocument(userId, type, file)` takes no such parameter. There is no `number` field
   anywhere for the licence number the approved design calls for either.
   **Fix**: add optional `number`/`expiresAt` request params to `POST /drivers/me/documents`, a
   `document_number` column (migration), and both fields on `DriverDocumentResponse`.
2. **No endpoint to read the current self-declaration text or the driver's own acceptance status.**
   `SelfDeclarationService` already has `hasAcceptedCurrentVersion`; nothing exposes it, and nothing
   exposes `DeclarationVersion.text` at all - the versioned consent text has no way to reach any client.
   **Fix**: `GET /drivers/me/declaration` → current version + text + the driver's own acceptance state.
3. **No bot-challenge hook on OTP request**, called for explicitly by this task. The DB-backed
   resend/send-rate limits already exist (incl. this session's per-number/IP/global SMS caps); what's
   missing is a pluggable, config-driven challenge step. **Fix**: `ChallengeVerifier` SPI (mirrors the
   `SmsSender` pattern from the prior task - no vendor chosen), `NoOpChallengeVerifier` default
   (disabled), wired into `OtpLoginService.requestOtp` ahead of the existing limits when enabled.

No other gaps found - vehicle, selfie, document listing, and the overall status fields all already
exist with the right shape.

## 3. Frontend

`frontend/driver-registration/` - same tooling as `frontend/admin` (React 19 + TypeScript + Vite,
`openapi-fetch` client generated from `docs/api/openapi.json`, oxlint, vitest, Playwright). Mobile-first
layout (single column, large touch targets, bottom-fixed primary action). Access token in memory only,
same `auth.ts` pattern as admin-web (never `localStorage`/`sessionStorage`). Resumable form state
(current step, text field values) may go in `sessionStorage` for a mid-session refresh - explicitly
**never** document/selfie image bytes or blobs, which live only in a `<input type="file">`/camera
capture's in-memory `File` object until upload succeeds.

## 4. Security

- Production CSP + a build-time test asserting no `unsafe-inline`/`unsafe-eval`/third-party sources -
  same pattern as `frontend/admin/scripts/check-production-csp.mjs`.
- No PII or tokens in URLs (no query-string phone numbers, codes, or tokens) or in any client log.
- `ChallengeVerifier` hook (§2.3) - config-driven, default disabled, no real provider wired.
- Upload limits already enforced server-side (`UploadValidator`: type/size/decodability/pixel-bomb
  check) - the client-side check is a UX improvement (fail before upload, not instead of the server
  check).
- Consent text slot is versioned (`declaration_versions.text`), explicitly **not** final legal wording -
  whatever is seeded is placeholder/test copy.
- **Test data only**: no config in this build enables real-data collection - `LADYGO_SMS_*`/real file
  storage credentials are deploy-time secrets this repo never sets a default for (confirmed in the
  prior SMS-adapter task), and this app ships no code path that would behave differently if they were
  set versus not - that's an existing, unrelated infra decision, not something this app adds or removes.

## 5. Tests

Component tests per screen/step. Playwright E2E: happy path (login → profile → vehicle → documents →
selfie → declaration → PENDING_REVIEW status) and negative paths (wrong file type, oversized file,
expired-insurance-flagged-at-review, rejected-with-note → resubmit → re-enters review). Each E2E test
gets its own seeded driver account, `retries: 0`, matching admin-web's established pattern. New CI
workflow, path-filtered to `frontend/driver-registration/**` and the backend paths this app's own tests
touch.
