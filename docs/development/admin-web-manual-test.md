# Admin web — manual test checklist

Status: v1.0 · for exercising the first working screens (docs/product/admin-web-design.md) against a
real local stack. Pairs with `scripts/dev-up.sh` / `scripts/dev-down.sh`.

## 0. Start the stack

```bash
scripts/dev-up.sh
```

Takes ~1-2 minutes (Postgres start, backend start, MFA enrolment, seed data, admin web dev server).
Prints, to **this terminal only** (never written to a file):

- The bootstrapped `SUPER_ADMIN` email, password, and TOTP secret.
- The seeded driver's phone number and vehicle registration.

Keep that terminal output visible - you'll need the TOTP secret for every login below. Compute a
fresh 6-digit code from it either with a real authenticator app (scan the `provisioningUri` printed,
or enter the secret manually) or with the Python snippet in
`docs/development/auth-local-runbook.md` §5 (codes are valid ±30s either side of "now" and are
one-time-use - compute a new one each time, don't reuse one across steps).

When finished: `scripts/dev-down.sh`.

## 1. Login with MFA

1. Open `http://localhost:5174`. You should land on `/login` (not signed in yet).
2. Enter the `SUPER_ADMIN` email and password. Submit.
3. A TOTP field should appear (the backend treats "password right, no/invalid code yet" and "wrong
   password" identically - this is deliberate, see `docs/security/auth-design.md` - so don't read
   anything into *why* it appeared, just that it did).
4. Enter a fresh TOTP code. Submit.
5. **Expect:** redirected to `/dashboard`, sidebar visible, "Sign out" button at the bottom of it.
6. Open devtools → Application → Cookies for `localhost:8080`. **Expect:** `ladygo_admin_rt`
   (HttpOnly - devtools will show this, you cannot read its value from the page's own JS console) and
   `ladygo_admin_csrf` (not HttpOnly) both present, both `Secure`.
7. In the JS console: `document.cookie`. **Expect:** `ladygo_admin_csrf` appears; `ladygo_admin_rt`
   does not (confirms it's actually HttpOnly, not just labeled as such).

## 2. Password/MFA edge cases (optional, skip if short on time)

- Reload the page after step 1. **Expect:** still signed in (silent session restore via the refresh
  cookie) without re-entering credentials - briefly shows "Checking session…" first.
- Sign out, then try logging in with the wrong password. **Expect:** a generic error, no hint of
  which field was wrong.

## 3. Driver review queue

1. Click "Verification" in the sidebar.
2. **Expect:** a table listing the seeded driver's documents (LICENSE, VEHICLE_REGISTRATION,
   VEHICLE_PHOTO, INSURANCE - all `PENDING`), submitted-at timestamp, and a link showing a truncated
   driver ID (known gap: no driver name in this response yet, see the commit that added this screen).
3. Click the driver-id link.

## 4. Driver review detail

1. **Expect:** vehicle section (JK01AB1234, CAB, PENDING), a document list, and a "Decision" panel
   showing "Not ready yet" with the missing requirements listed (at minimum: SELFIE review, since the
   seed script uploads it but it's reviewed via the same flow).
2. Click "View" on the LICENSE document.
3. **Expect:** an image appears below the button (a small solid-color square - the seed image, not a
   real license, with a watermark bar overlaid at the bottom showing your admin name and a timestamp).
   Confirm the watermark text matches whichever `SUPER_ADMIN` you're logged in as.
4. Open devtools → Network, find the request to `.../driver-documents/{id}/view`, and the image
   request that follows it. **Expect:** the image URL contains `driver-document-derivatives/`, not
   the original document's own key - confirms you're looking at the watermarked derivative, never the
   raw original.
5. **URL expiry:** copy the image URL, wait about 90 seconds (the default TTL is 60s, plus the
   backstop delete fires 5s after that), then open the copied URL directly in a new tab. **Expect:**
   it no longer loads (expired presigned URL, and the derivative itself has since been deleted).
6. Click "Approve" on one document. **Expect:** its status flips to `APPROVED` without a page reload.
7. Click "Reject" on a different document, enter a reason in the prompt. **Expect:** its status flips
   to `REJECTED`; cancelling the prompt (no reason) leaves it unchanged.
8. Approve the remaining documents (and the vehicle, via its own Approve button) until "Decision"
   shows "Ready for approval."
9. Click "Approve driver". **Expect:** no error; the verification status line at the top updates.

## 5. Ride/flag oversight, audit log, config (backend-only so far - no screens yet)

These three have working, tested backend endpoints but no admin-web screens yet (next phase of work).
Confirm they're reachable with the access token from step 1 (devtools console, after copying the
token - or `curl` with a Bearer header):

- `GET /api/v1/admin/rides` - paginated ride list.
- `GET /api/v1/admin/ride-flags` - paginated flag list; `POST /api/v1/admin/ride-flags/{id}/review`
  marks one reviewed (idempotent - calling it twice on the same flag 400s the second time).
- `GET /api/v1/admin/config` - lists every config entry. Try `PUT /api/v1/admin/config/{id}` with a
  value outside a safety-relevant key's bounds (e.g. `999999` for the entry whose key is
  `ride.gate.pickup_radius.m`) - **expect** a 400 rejecting it, and the stored value unchanged.
  Try the same call with a plain `ADMIN` token instead of `SUPER_ADMIN` - **expect** a 403.

## 6. Session timeout

1. Stay signed in but don't touch the mouse/keyboard for 15 minutes (the idle timeout).
2. **Expect:** automatically signed out, redirected to `/login`, cookies cleared.

(To check this faster without actually waiting 15 minutes: in devtools console,
`localStorage` isn't used for this, so there's nothing to fast-forward by editing storage - the
timer is in-memory only. Skip this step if short on time, or temporarily lower
`IDLE_TIMEOUT_MS` in `frontend/admin/src/lib/idleTimeout.ts` for a local-only test run, and revert
before committing anything.)

## 7. Logout

1. Click "Sign out".
2. **Expect:** redirected to `/login`; the `ladygo_admin_rt`/`ladygo_admin_csrf` cookies are cleared
   (devtools → Cookies, both gone).
3. Try reloading `/dashboard` directly. **Expect:** redirected back to `/login` (no stale session).
