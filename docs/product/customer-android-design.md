# Customer Android app - design and slicing

Status: slices 1 and 2 built (2026-10-17); slice 3 designed here, not built. Module: `mobile/android-customer`
(`com.ladygo.customer`, Kotlin, Compose, MVVM, Hilt, Retrofit + Moshi, `minSdk 26`). It reuses the driver app's
proven infrastructure verbatim (`docs/product/driver-android-design.md` is the reference for the shared
decisions: server is the only source of truth, encrypted token storage, single-flight refresh, generated
OpenAPI client committed and drift-checked, ktlint + detekt + lint + release build in CI).

## 1. Contract facts that shape the app (from `docs/api/openapi.json`, `docs/api/booking.md`)

- Login: OTP with `intendedRole=CUSTOMER`, `purpose=LOGIN` (delivered over WhatsApp for the pilot).
- A booking **requires** `rideSelfieId` - a fresh front-camera selfie uploaded per booking
  (`POST /customers/me/ride-selfies`, multipart `file`) - plus `expectedFare` from the estimate the rider
  saw (the server rejects a stale fare with `FareChangedException`), `paymentMethod` (`CASH` only for the
  pilot; `ONLINE` is rejected), and exact pickup/destination coordinates + addresses.
- One-time self-declaration (`POST /customers/me/declaration {declarationVersion}`) and a profile name.
  **Backend gap found and closed in slice 1:** riders had no way to read the declaration text/version
  (only drivers did). Added `GET /api/v1/customers/me/declaration` (same `DeclarationStatusResponse`).
- Ride progress arrives as `BookingDetailResponse` over STOMP `/user/queue/booking` and via
  `GET /bookings/{id}`; the rider sees `ridePin` (driver reads it back), driver summary, status.
- Trip sharing: `POST /bookings/{id}/trip-shares` -> OS share sheet; `DELETE .../{shareId}` revokes. There
  is **no list endpoint** yet (pilot plan section 4 flags it), so "manage active shares" waits on it.

## 2. Decisions

1. **Locations without a Maps SDK/key for the pilot.** Pickup = device location (fused location) with the
   address from the platform `Geocoder`; destination = typed address resolved by `Geocoder`, plus the
   rider's saved locations. No map surface, same stance as the driver app (navigation is a hand-off).
   Risk: `Geocoder` quality on Srinagar addresses is unverified - the roadmap's "check 50-100 local points"
   task (D4) applies; the abstraction (`LocationResolver`) lets Places/Maps replace it later.
2. **Selfie capture with CameraX front camera, per booking**, JPEG kept in memory/cache only until uploaded,
   never in the gallery; `FLAG_SECURE` on the capture screen.
3. **Pilot payment = cash only**; the booking screen shows no online option.
4. **No ride state on disk** except the active booking id (UUID) for resume-after-kill, same as the driver app.
5. Android first; iOS customer app after the pilot (roadmap).

## 3. Slices

- **Slice 1 (built):** scaffold from the driver infrastructure; OTP login as `CUSTOMER`; onboarding gate
  (profile name -> declaration text -> accept) driven entirely by server state; placeholder Home with logout.
- **Slice 2:** book a ride - location permission + current location, destination search, saved locations,
  fare estimate, front-camera selfie, create booking (cash), searching state with cancel, driver assigned.
- **Slice 3:** ride in progress - live status via `/user/queue/booking` (STOMP) + polling fallback, ride PIN
  display, driver/vehicle card, confirm-arrival, end-early, safety cancel, Call 112, trip-share (create +
  share sheet), cash dispute, history.

## 4. Slice 1 behaviour

`Login -> Onboarding -> Home`. Onboarding asks the server (`GET /customers/me`, `GET /customers/me/declaration`)
and shows exactly one step: **name** (blank/missing name), **declaration** (text + version from the server;
the version sent back is the one shown), or proceeds only on a server-confirmed `Ready`. Every failure
(network, 5xx, missing declaration text) is a retryable error, never a pass-through. A rejected step shows the
server's message and stays on the step; success re-fetches so the server decides what comes next.

## 5. Slice 2 behaviour (built)

`Home -> Book -> Track`. **Book**: pickup = device location (fused, fine permission requested in context) with the
platform-geocoded address; destination = typed query (>= 3 chars) resolved by `Geocoder`, biased to a ~55 km box
around the rider; the server prices it (`POST /bookings/estimate`, CAB only) and decides the service area -
`OUT_OF_SERVICE_AREA`/distance rejections show the server's message. **Book ride** needs a fresh front-camera selfie
(CameraX; JPEG held in memory only, uploaded as multipart through a hand-written `RideSelfieApi` because the
generated client mis-models that endpoint as JSON; `FLAG_SECURE` on the capture screen). A selfie is reused only
within 25 min (server window 30). **Create** sends `paymentMethod=CASH`, `expectedFare` = the quote shown, and an
`Idempotency-Key` that is kept across retries of the same confirmation (so a lost response can never double-book) and
replaced only when the rider re-confirms a changed fare. `FARE_CHANGED` shows the fresh quote and the message for
re-confirmation - never auto-accepted; the body is read once (`ResponseBody` is single-use) to get both error and quote.
**Track**: polls `GET /bookings/{id}` every 5 s while visible (STOMP replaces this in slice 3); stages are derived from
the server status; cancel (reason optional, default "Changed my mind") only while searching / driver coming, the server
enforces it; ride PIN + driver/vehicle card with `FLAG_SECURE`; Call 112 while driver coming / in trip; stale polls
cannot drag a cancelled/started ride back. **Resume after a kill**: Home asks the server for the rider's newest
non-terminal booking (`GET /bookings`, no local persistence) and offers "View my ride".

Not in slice 2: saved locations, scheduled rides, live push, trip share, end-early / safety cancel / confirm-arrival
/ cash dispute, history screen (all slice 3 or later).

### 5.1 Fresh-context review of slice 2 - what it found and what changed

The backend has **no one-active-booking-per-customer guard** (only `(customer, Idempotency-Key)` de-duplication), so
every client path that loses the key while an earlier create may have landed can create two live rides. Fixed, each
with a regression test: Back / "Change destination" are blocked while a request is in flight or its outcome is unknown
(`createUncertain`), and an unknown outcome first asks the server whether the ride landed (adopt it) before allowing a
retry of the *same* key; Back during a selfie upload no longer auto-books; Home no longer re-enables "Book a ride" on a
failed lookup (tri-state active-booking result); rotation no longer re-locates and blanks the estimate; double taps use
`launchSingleTop`; the camera is unbound when the selfie step is left; the selfie is captured **in memory** (no CameraX
temp file) and downscaled to <= 1280 px / JPEG 85 and rotated upright (`SelfieEncoder`); location asks FINE+COARSE
together and both permission dead-ends get an "Open settings" button; track polling keeps one in-flight fetch and stops
when the ride is over; the cancel dialog warns that a fee may apply once a driver has accepted.

Backend fix found on the way: Spring's default multipart limit is **1 MB per file**, silently below the service-level
8 MB image limit - phone selfies / document photos over 1 MB were rejected before the service saw them (this also
affected driver-registration uploads). `spring.servlet.multipart.max-file-size: 8MB` / `max-request-size: 10MB` added.

Follow-ups (not built): a server-side guard against a second non-terminal booking (409); `consumes = MULTIPART` on the
ride-selfie controller so the generated client is correct; whether the cancel dialog should quote the exact fee.
