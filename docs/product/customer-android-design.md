# Customer Android app - design and slicing

Status: slice 1 built (2026-10-17); slices 2-3 designed here, not built. Module: `mobile/android-customer`
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
