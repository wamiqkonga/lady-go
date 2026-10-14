# Driver Android app — design

Status: design only, per instruction - nothing built. `mobile/android-driver/` already has a Phase 1
skeleton (Kotlin, Compose, MVVM, `applicationId com.ladygo.driver`, `minSdk 26`, cleartext disabled in
release with a debug-only `10.0.2.2`/`localhost` exception, `android:supportsRtl="true"` already set).
This design builds on that skeleton, not a new tree - the task's `mobile/android/driver` path doesn't
exist in this repo; using the real one (flagged again in §6).

Contract: `docs/api/openapi.json`, confirmed against the actual controllers (not the draft realtime doc -
see the callout in §2). Every field/enum/config value below is taken from the real backend code, not
assumed.

## 0. One correction to the existing realtime design doc

`docs/architecture/realtime-architecture.md` (status: "Draft v0.1 · Phase 0") describes location ingest
as `WS STOMP /app/driver/location`, driver-side cadence ~10s idle / ~3-5s on-ride, with a Redis
GEO/pub-sub relay to customers. **None of that shipped.** There is no `@MessageMapping` anywhere in the
backend - location ingest is `POST /api/v1/drivers/me/location` (plain REST), rate-limited server-side
to exactly 1 update per `driver.location.rate.window.seconds` (= 2s) via `driver.location.rate.max.attempts`
(= 1), and nothing relays it onward to customers yet (matches `roadmap.md`: "Phase 7 (PARTIAL) - live
tracking UI and admin live map not started"). This design follows the real, shipped contract (REST,
2s throttle) - not the draft doc's plan. The draft doc is about customer-facing live tracking, which
this app doesn't need to consume at all; it only ever *sends* its own location.

## 1. Screens and navigation

Single-activity Compose, one `NavHost`. Top-level graph, gated by auth + onboarding state (computed on
every cold start and every reconnect, never cached as a client-side source of truth - see §3):

```
Login (phone → OTP) ──► Onboarding status gate ──► Home
                              │                        │
                      (NOT_SUBMITTED/                  ├─► Incoming offer (full-screen, countdown)
                       PENDING_REVIEW/                 │        │ accept ──► Active ride
                       REJECTED/SUSPENDED)              │        └ decline/expire ──► back to Home
                      → read-only status screen,       │
                        same shape as the driver-      └─► Active ride (DRIVER_ACCEPTED..RIDE_STARTED..COMPLETED)
                        registration-web dashboard             │
                        (no separate onboarding flow            ├─► To pickup (arriving)
                        built in this app - see §6)              ├─► Arrived (PIN entry)
                                                                   ├─► In trip (started)
                                                                   ├─► Complete + cash confirm (if CASH)
                                                                   └─► Early end (reason + safety flag)
                                                        │
                                                        └─► History / earnings (list, basics only)
```

- **Login**: phone → `POST /auth/otp/request` (`intendedRole: DRIVER`) → code →
  `POST /auth/otp/verify` → `TokenResponse{accessToken, refreshToken}`. Same body-based refresh-token
  shape as the driver-registration website (no HttpOnly cookie option for this API family) - tokens go
  to `EncryptedSharedPreferences`/Keystore here, not memory-only, because a mobile app is expected to
  survive a process restart without forcing a fresh OTP every time (see §3).
- **Onboarding status gate**: `GET /drivers/me` → `verificationStatus`. Anything other than `APPROVED`
  routes to a read-only status screen (profile/vehicle/documents/declaration, mirroring
  `docs/product/driver-registration-web.md`'s dashboard) with a link/QR to the driver-registration
  website for actually completing or fixing onboarding - **this app does not reimplement document
  upload, camera capture, or the self-declaration flow**; that's the website's job (decided in §6: that
  split is confirmed, not duplicated here).
- **Home**: online/offline toggle (`PUT /drivers/me/availability`), today's basic stats - **no in-app
  map** (decided in §6: navigation is a hand-off to Google Maps via intent, so this screen has no map
  surface to render at all). Toggling ONLINE starts the
  foreground location service (§2); toggling OFFLINE stops it and calls the endpoint with
  `status: OFFLINE` first (server-authoritative order: stop sending location only after the server has
  accepted OFFLINE, not before, so a late in-flight location update while nominally "offline" doesn't
  reach a driver the server still thinks can be dispatched to - see §3's reconciliation rule).
  **Confirmed, not assumed** (`DriverAvailabilityService`/`DriverRepository`/`RideStatusSyncListener`):
  `setAvailability` only ever accepts `ONLINE`/`OFFLINE` from the driver directly and explicitly refuses
  to change availability away from `BUSY` - a driver is claimed `ONLINE→BUSY` atomically when an offer
  is sent and stays `BUSY` for the *entire* ride (accept through completion); the server auto-releases
  `BUSY→ONLINE` itself on both ride completion and ride abandonment/cancellation
  (`RideStatusSyncListener.onRideCompleted`/`onAbandoned`), so the app never needs to call the
  availability endpoint to "finish" a ride - only the explicit online/offline toggle ever needs it. The
  `ON_RIDE` enum value exists in `DriverAvailability` but is never actually assigned anywhere in the
  current backend - `BUSY` is the one state used throughout. This app's "online" toggle should treat
  `BUSY` the same as `ONLINE` for display purposes (driver is still "on duty," just mid-ride, not a
  state the toggle itself can leave) and let the ride-state reconciliation in §3 be the one true source
  for whether a ride is in progress.
- **Incoming offer**: pushed via STOMP `/user/queue/offers` (`OfferResponse`: `offerId`, `bookingId`,
  `vehicleType`, `approximatePickupLatitude/Longitude` - grid-snapped to
  `booking.pickup.approximate.radius.m`, never exact - `estimatedFare`, `currency`, `distanceMeters`,
  `expiresAt`). Full-screen, un-dismissable except by accept/decline/expiry, loud notification/vibration.
  Countdown is `expiresAt - serverNow` (§2's clock-skew handling), not a client-started timer - an offer
  that expires mid-tap is handled by treating the accept call's own failure as authoritative (§3).
- **Active ride**, one screen per `BookingStatus`, not six separate screens with duplicated chrome -
  shared scaffold (status bar, participant info - **no in-app map**, per §6's navigation decision; a
  "Navigate" button hands off to Google Maps via intent instead) with status-specific content:
  - `DRIVER_ACCEPTED`/`DRIVER_ARRIVING` → "to pickup" (exact address now visible, from
    `GET /drivers/me/bookings/{id}` - pickup becomes exact only after acceptance, per
    `docs/api/matching.md` §3/§6) + a **"Confirm arrival"** action → `POST .../rides/{id}/arriving` then
    `.../arrived` once the server-side proximity+freshness gate passes (driver app does not do its own
    distance math to decide when to allow "arrived" - it calls the endpoint and shows the server's
    actual error if the gate rejects it, e.g. too far/stale location - never trust a client-computed
    "close enough").
  - `DRIVER_ARRIVED` → rider selfie shown here (`GET .../rider-selfie`, a short-TTL signed URL to a
    watermarked derivative, `verification.rider_selfie.view_url.ttl.seconds`-bounded - re-fetched fresh
    if the screen is revisited, never cached past that TTL) + PIN entry (`POST .../start` with
    `{pin}` - the rider reads their own PIN aloud; a wrong PIN surfaces the server's real rejection,
    including lockout after `ride.pin.driver.max.attempts` in `ride.pin.driver.window.minutes`).
  - `RIDE_STARTED` → in-trip (turn-by-turn handed to an external nav app - open question in §6),
    "Confirm cash collected" visible only when `paymentMethod == CASH` (`POST .../confirm-cash-collected`),
    "End ride early" (reason text + `safetyConcern` boolean → `POST .../end-early`).
  - `RIDE_COMPLETED`/`PAYMENT_PENDING`/`COMPLETED` → completion summary (fare, cash-confirm state).
  - One-tap **Call 112** button, visible on every active-ride screen state (§4) - `Intent.ACTION_DIAL`
    to the native dialer, nothing platform-side (no SOS backend exists yet - deferred, a public-launch
    gate per `docs/development/roadmap.md`'s launch-gates table, not something this app can substitute
    for).
- **History/earnings**: `GET /drivers/me/rides/history` (paginated) - list + per-ride fare, nothing
  beyond that for this pass (no payout/statement UI - out of scope, matches roadmap Phase 8).

## 2. Architecture

- **Single-activity Compose**, `NavHost`-driven, MVVM + unidirectional state: each screen has one
  `ViewModel` exposing a single `StateFlow<UiState>` (sealed class per screen, e.g.
  `ActiveRideUiState.ToPickup/Arrived/InTrip/...`) and a `(UiEvent) -> Unit` sink - no two-way binding,
  no `LiveData`. Navigation itself is driven by observing the ride state machine (§3), not by screens
  calling `navController.navigate()` ad hoc from business logic.
- **Hilt** for DI (not yet a dependency - added this phase). One `@Singleton` `TokenStore`, one OkHttp
  client with an auth interceptor (attaches the access token, triggers a refresh-and-retry on 401 using
  the same `POST /auth/refresh` the driver-registration website uses, single-flight so concurrent 401s
  don't each fire their own refresh), one Retrofit instance built against the **generated** client (§2.1).
- **Coroutines/Flow** throughout; no RxJava. `WorkManager` is explicitly *not* used for location (it's
  not built for 2-second-cadence work); the foreground service (§2.2) owns the location loop directly.
- **Room**: not used in this pass. Nothing here needs offline-first persistence beyond what
  `EncryptedSharedPreferences` already covers (tokens) and what the server remains the source of truth
  for (ride/offer/onboarding state, always re-fetched on reconnect). Add it later only if a concrete
  need appears (e.g. caching ride history for true offline viewing) - no speculative schema now.

### 2.1 Generated API client

`openapi-generator` (Kotlin + `kotlin-retrofit2` or similar, matching whichever generator the web apps'
`openapi-typescript` plays the equivalent role for) against `docs/api/openapi.json`, wired into the
Gradle build (a generation task, output under `build/generated/`, never committed - same "generated,
not committed, drift-checked in CI" posture as `frontend/admin/src/lib/api-types.ts`). CI step: generate
fresh into a temp dir, diff against what the module actually builds with (or simply always regenerate
as part of the build and fail the build if the spec is unparseable/the generated code doesn't compile) -
concretely: a Gradle task that runs codegen and a CI step that fails if `git diff` on the generated
output directory is non-empty, mirroring `frontend/admin/scripts/check-openapi-types.mjs`'s "regenerate
fresh, diff, fail if different" shape, adapted to wherever generated Kotlin lands.

### 2.2 STOMP client

`@stomp/stompjs`-equivalent for Android: a maintained JVM STOMP-over-WebSocket client (e.g. `krossbow` or
a thin wrapper over `OkHttp` WebSocket + raw STOMP framing - open question in §6 on exact library, not a
design-blocking one). Design requirements, all server-confirmed in §0/`realtime-architecture.md` §6:

- Handshake: `Authorization: Bearer <accessToken>` header on the WebSocket upgrade request (the backend
  explicitly never accepts a token via query string - `AuthHandshakeInterceptor`). Connect only while
  the access token is valid; a 401 at handshake means refresh-then-reconnect, not a bare retry.
  Reconnect with **exponential backoff** (e.g. 1s, 2s, 4s, 8s, capped ~30s, plus jitter), consistent with
  the architecture doc's own stated reliability rule.
  - A per-user connection limit exists server-side (`websocket.max_connections_per_user`, default 3) -
    the app must actually close its old socket before/when opening a new one (app backgrounding +
    foregrounding, or a cold reconnect after a crash) rather than leaking connections that eventually
    get this driver rate-limited out of their own reconnect.
- On every (re)connect: subscribe to `/user/queue/offers` and `/user/queue/booking`, **then**
  unconditionally call `GET /drivers/me` (profile/availability), and if there's a `bookingId` the app
  was last tracking, `GET /drivers/me/bookings/{id}` - reconciling local state to the server's, exactly
  as §3 requires. Missed-while-disconnected STOMP messages are never specially recovered; the
  reconciliation fetch is what makes that safe (same "resubscribe then fetch current state" rule the
  architecture doc states for the general case).
- **Server-time-based offer expiry**: on receiving an `OfferResponse`, compute `serverClockSkew` from
  the response's own `Date` header (or a dedicated `/system/ping`-style endpoint - `GET /api/v1/system/ping`
  already exists and is public) at connect time, and drive the countdown off `expiresAt - (localNow -
  serverClockSkew)`, not off a client-started stopwatch that assumes the push arrived instantly. On
  timer-zero, optimistically show "expired" but still let a fired accept-tap complete if it was already
  in flight (§3 - the server's own response is authoritative either way).
- Heartbeats: standard STOMP heartbeat negotiation (e.g. 10s/10s) so a half-dead connection (TCP alive,
  app in a doze-throttled background state) is detected and reconnected rather than silently hung.

### 2.3 Foreground service + location

- **Decided (§6): the service runs only while the app is visible** - no `ACCESS_BACKGROUND_LOCATION`
  request, no background-location permission dialog at all for the pilot. A `ForegroundService` (type
  `location`, per Android 14+'s mandatory foreground-service-type declaration) starts when the driver
  goes ONLINE *and* the app is in the foreground/visible lifecycle state, and stops whenever either
  condition stops holding - the app leaving visibility, or an explicit OFFLINE toggle - whichever comes
  first. The ONLINE/OFFLINE preference itself is retained (not reset) across visibility changes, so the
  service resumes automatically the moment the app becomes visible again while still toggled ONLINE.
  Practical consequence: a driver who backgrounds the app while ONLINE stops sharing location (and so
  stops being dispatchable) until they bring the app back to the foreground - an explicit, accepted
  trade-off for the pilot to avoid Play's background-location review/disclosure burden, not an oversight.
  In-app messaging ("keep Lady Go open to receive rides") is a slice 2 product/UX item, not covered here.
- Permission flow: `ACCESS_FINE_LOCATION` only (no background-location permission exists in this design).
  The ONLINE toggle is disabled with an explanatory state (not a silent no-op) until it's granted.
  Denial doesn't crash the flow; it routes to a settings-deep-link screen explaining why fine location is
  required to go online.
- **Throttle: exactly matches the server's 1-per-2s limit** - the service's own location-update interval
  is 2s while ONLINE/ON_RIDE (not faster; sending faster just wastes battery hitting a 429 the server
  already rate-limits). `FusedLocationProviderClient` with `PRIORITY_HIGH_ACCURACY`, `setMinUpdateIntervalMillis`
  aligned to the same 2s. A locally-queued single "pending" update (not a full offline queue - Room was
  explicitly declined above) is retried on the next tick if a send fails, so a brief network blip doesn't
  need its own retry/backoff machinery beyond "try again next cycle."
- Doze/battery: request `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` only while genuinely ONLINE (never
  pre-emptively at install/login) with a clear explanation, since the foreground service + its own
  persistent notification already exempts the critical path from most Doze restrictions - this is a
  belt-and-suspenders ask for devices/OEMs with aggressive non-stock battery management, not a hard
  requirement for correctness.
- Accuracy: `DriverLocationRequest` requires `accuracyMeters` (non-null outside the dev profile) - a
  fix with no accuracy reading (rare, but possible transiently) is simply not sent that cycle rather
  than sent with a fabricated value.

## 3. State machine - server is the only source of truth

The app's ride state is **derived**, never authored client-side. One `RideStateMachine` (pure function:
`(currentState, serverBookingStatus) -> ClientRideState`) maps the server's `BookingStatus` enum 1:1 to
a UI state (`REQUESTED`/`SEARCHING_DRIVER` never apply to an *accepted* driver's own view, so the
driver-side machine only ever starts at `DRIVER_ACCEPTED`):

```
DRIVER_ACCEPTED → DRIVER_ARRIVING → DRIVER_ARRIVED → RIDE_STARTED → RIDE_COMPLETED → PAYMENT_PENDING → COMPLETED
                                                                                   ↘ CANCELLED (any point, server-driven)
```

Rules, all enforced by never trusting a locally-optimistic transition as final:

1. **Every state-changing tap calls the server first, UI updates from the response, not from the tap.**
   A button shows a busy/disabled state while in flight; on success, the new `BookingDetailResponse`
   (which carries `status`) re-drives the state machine; on failure, the UI reverts to whatever the last
   *confirmed* server state was and surfaces the real error (e.g. "ride PIN lockout", "too far from
   pickup, move closer and retry").
2. **On every app foreground/resume, and after every reconnect**, re-fetch the current booking (if the
   app believes one is active) and reconcile - if the server disagrees with the locally-remembered
   state (e.g. the booking was cancelled while the app was backgrounded, or the ride lifecycle moved
   further than the app's cached view realizes because a push was missed), the server wins outright: the
   UI jumps straight to whatever screen the real status implies, no "catching up" animation through
   intermediate states that didn't actually happen on this device.
3. **No action is offered that the current confirmed-server state doesn't actually allow** - e.g. "Start
   ride" never renders before a confirmed `DRIVER_ARRIVED`, because the button's visibility is a pure
   function of the reconciled state, not of "the driver tapped arrived a moment ago so it's probably
   fine now."
4. **App kill**: no ride/offer state persisted to disk at all (deliberately - see Room decision above).
   On next launch: `GET /drivers/me` → if `verificationStatus != APPROVED`, status gate; else
   `GET /drivers/me/offers/current` (the HTTP polling fallback the backend explicitly documents for
   "in case the WebSocket push never arrived") to check for a live offer, and if the driver's own
   availability was `ON_RIDE` server-side, fetch the active booking the same way reconnect does (item 2).
   A driver who force-closes mid-ride resumes exactly where the server says they are, not where they
   were when killed.
5. **Network loss**: STOMP reconnect loop (§2.2) handles the realtime channel; any in-flight REST call
   that fails due to connectivity is surfaced as a retryable error on the *current* screen, never
   silently assumed to have succeeded or failed - the user sees "couldn't reach the server, retry" and
   the state machine does not advance until a server response actually arrives.
6. **Token expiry**: the OkHttp auth interceptor refreshes proactively (same ~5-minutes-before-expiry
   schedule the web apps use) and reactively on a 401. If refresh itself fails (revoked/expired refresh
   token), the app logs out to the Login screen - cleanly, not mid-action; an in-flight ride action that
   hits an un-refreshable 401 shows "please sign in again," not a silent failure.
7. **Offer expiring mid-tap**: the accept button is disabled the instant local countdown hits zero (UX
   signal only), but a tap that was already in flight when the timer hit zero is allowed to complete -
   its *response* is authoritative. If the server's answer is "offer no longer available" (already
   expired/claimed/withdrawn server-side), the app shows that plainly and returns to Home/waiting-for-
   offers - never shows a false "accepted" state from a client-side assumption that the tap landed in
   time.

## 4. Security

- **Tokens**: `EncryptedSharedPreferences` (Jetpack Security, AES256-GCM, Keystore-backed master key) -
  access + refresh token both, consistent with this API family returning the refresh token in the
  response body (not a cookie, per §1's Login section) and a mobile app's expectation of surviving a
  process death without a fresh OTP every time (unlike the driver-registration *website*'s deliberate
  memory-only choice, made for a different threat model - a browser tab with no OS-level sandboxing
  equivalent to an Android app's own data directory).
- **No secrets in the repo**: Maps/any other provider key via `local.properties` (gitignored, already
  the project's established pattern for local dev secrets) for local builds, and a CI secret injected
  as a Gradle property for release builds - mirrors the existing `LADYGO_API_BASE_URL` pattern already
  in `app/build.gradle.kts`.
- **`FLAG_SECURE`** on the rider-selfie view and the PIN-entry screen (blocks screenshots/screen
  recording and non-secure screen mirroring of the one image/credential genuinely sensitive enough to
  warrant it on this app - everything else is either public-facing or already masked server-side).
- **Cleartext traffic disabled in release** - already true in the existing skeleton manifest
  (`android:usesCleartextTraffic="false"`, overridden only in the debug-build-type manifest for the
  emulator-host exception); nothing to add here, confirming it stays that way.
- **No PII or tokens in logs**: a lint/detekt rule (custom or off-the-shelf) banning raw `Log.d`/`println`
  of request/response bodies in release-buildable code paths; any debug logging of network traffic goes
  through OkHttp's `HttpLoggingInterceptor` at `BODY` level **only** in debug builds, never release
  (`BuildConfig.DEBUG`-gated), same reasoning as every backend "never log the OTP/token/full phone"
  rule from this session's earlier work.
- **Release build / R8**: `isMinifyEnabled = true` already set in the skeleton's release build type;
  `proguard-rules.pro` needs real keep-rules once Retrofit/the generated client/Hilt are added (reflection-
  based libraries routinely need them) - a build-time check, not a design decision: the release build
  must actually run on a real device/emulator before shipping, not just compile.
- **Certificate pinning**: **decided (§6) - no pinning for the pilot**, revisit with backup pins before
  public launch. Pin-rotation operational risk outweighs MITM protection at pilot scale for now; this is
  a time-boxed decision, not a permanent one.
- **Call 112**: `Intent(Intent.ACTION_DIAL, Uri.parse("tel:112"))` (`ACTION_DIAL`, not `ACTION_CALL` -
  opens the dialer pre-filled rather than silently placing the call, so the driver has a final
  confirmation tap and the app never needs `CALL_PHONE` permission at all). No backend involvement,
  no SOS record created - explicitly not a substitute for the deferred SOS feature (`docs/development/
  roadmap.md` launch-gates table), just the same "native dialer, no platform involvement" interim
  affordance already decided for the rider app's trip-sharing feature.

## 5. Test plan

- **Unit tests**: the `RideStateMachine` reducer (pure function, exhaustively tested per `BookingStatus`
  transition, including "server state the app never locally predicted" reconciliation cases) and every
  other reducer/mapper (offer countdown math against a fake clock, location-throttle interval logic).
- **ViewModel tests**: fake `ApiClient` (hand-written fake implementing the generated client's
  interface, not a mocking-framework proxy around it - cheaper to keep in sync and makes "what does the
  server actually return" explicit in test setup) and a fake STOMP client (a simple
  `Flow<StompEvent>` source the test pushes into) - covers accept/decline, reconnect-reconciliation,
  token-refresh-then-retry, and the offer-expires-mid-tap race from §3 item 7 directly (push an
  `OfferResponse` with `expiresAt` in the past, assert the accept call still fires and its response, not
  the countdown, decides the outcome).
- **Compose UI tests**: one test per screen (`createComposeRule`) asserting the right actions are
  visible/enabled for a given injected `UiState` - e.g. "Start ride" never composed unless state is
  `DRIVER_ARRIVED`-derived, `FLAG_SECURE` actually set on the PIN/selfie screens (a real, assertable
  window-flag check, not just "the code says so").
- **Contract test against the OpenAPI spec**: same shape as the backend's own `OpenApiSpecDriftTest` /
  the web apps' `openapi:check` - a CI step that regenerates the Kotlin client from the checked-in
  `docs/api/openapi.json` and fails the build if the generated output differs from what's actually
  committed/used (§2.1) - this is the "contract test," not a separate hand-written one, since a
  generated-client drift check already proves the app's types match the real API shape.
- **Static analysis in CI**: `ktlint` (formatting), `detekt` (the no-PII-logging custom rule, complexity/
  style defaults), Android Lint (`lintRelease`, treating new warnings as build failures for anything
  security-relevant - cleartext/permission/exported-component classes of lint rule specifically, not
  blanket-failing on every style nit).
- **What an emulator-based E2E against the dev backend would cover** (not built this pass - listed per
  instruction): login via a real OTP (same `LoggingSmsSender`-log-reading trick this session's web E2E
  suites use), go online and receive a real offer pushed over a real STOMP connection, accept it, walk
  the full ride lifecycle against the real backend (arrive → PIN → start → complete/cash-confirm),
  kill-and-relaunch mid-ride and confirm state reconciles correctly, force a token expiry and confirm
  silent refresh, and a real network-loss/reconnect cycle (toggle the emulator's network off/on) -
  exactly the negative paths §3 is designed around, proven against the real backend rather than fakes.
  Would need its own CI workflow (path-filtered to `mobile/android-driver/**`, an Android emulator
  runner, and a seed script reusing this session's `scripts/ci/*-seed.sh` pattern) - scoped as future
  work, not part of this design-only pass.

## 6. Decisions

Recorded 2026-10-14, in response to the open questions above. These are now settled for the pilot and
supersede the corresponding bullets elsewhere in this doc (§1, §2.3, §4 updated in place to match).

1. **Navigation**: hand-off to Google Maps via `Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=<lat>,<lng>"))`
   (falls back to a generic geo: URI if Maps isn't installed). No in-app map, and therefore **no Maps
   SDK/display key needed at all** - the map-provider-billing question is moot, not just deferred.
2. **`minSdk` 26** confirmed as-is (already the skeleton default, matches roadmap D15).
3. **Distribution**: Play Store internal testing track as primary, direct APK sideload kept as a fallback
   (e.g. a reviewer-turnaround blocker right before a pilot date). Both need to work, not just one.
4. **Localisation**: English first for the pilot; all user-facing strings externalised to `strings.xml`
   from the start (no hardcoded text) so Urdu is a resource-file addition later, not a refactor. Urdu
   itself is explicitly deferred to a later slice, not this one.
5. **Onboarding split confirmed**: this app shows a read-only status screen only; the driver-registration
   website remains the sole place onboarding actions (upload, selfie, declaration) happen. Not rebuilt
   natively here.
6. **Certificate pinning**: none for the pilot; revisit with backup pins before public launch. See §4.
7. **Location foreground service permission model**: the service runs only while the app is visible, so
   only `ACCESS_FINE_LOCATION` is requested - no `ACCESS_BACKGROUND_LOCATION`, no Play background-location
   review/disclosure burden for the pilot. Trade-off: a driver who backgrounds the app while ONLINE stops
   being dispatchable until it's foregrounded again. See §2.3 for the full consequence and the deferred
   in-app-messaging follow-up.
