# Driver app slice 3 (ride loop): fresh-context review, 2026-10-23

Read-only adversarial review by a fresh Opus agent, every claim checked against app and backend code. Status column is updated as fixes land (step 1 of the Android public-launch plan).

## Baseline findings (root causes)

| # | Finding | Root cause | Status |
|---|---|---|---|
| F1 | Offer notification says "INR 12764" | `OfferNotifier.formatFare` printed raw paise | Fixed, test `OfferNotifierTest` |
| F2 | App shows Offline while server has driver BUSY, offer never shown | App never reads availability back; 403 "BUSY" on Go online only shows an error, no STOMP connect, no offer fetch | Fixed: 403 BUSY now connects STOMP and shows info; logout sends OFFLINE; plus ghost-online fix (OFFLINE sent at start and at ride end). Unit-tested; not proven end to end, see baseline doc |
| F3 | Dead-end "Application under review" | No refresh, no logout | Fixed (refresh on resume, Check again, Log out); support contact deferred to step 3 |

## New findings

| # | Sev | Finding | Plan |
|---|---|---|---|
| 1 | CRITICAL | After app kill mid-ride, Home stops the location service (local state OFFLINE), so arrive/start fail the 30 s freshness gate forever | FIXED, verified on emulator |
| 2 | CRITICAL | Lost accept response orphans the ride (driver BUSY, rider waiting, no current-booking endpoint) | Fixed in client (re-fetch booking after failed accept), unit-tested only. Backend idempotent accept: later |
| 3 | CRITICAL | No exit from the ride screen before trip start (no-show, locked PIN) | Fixed (button + dialog shown on emulator; end-to-end cancel not exercised), unit-tested |
| 4 | IMPORTANT | Forced logout mid-ride erases saved ride pointer | Fixed, unit-tested |
| 5 | IMPORTANT | Accepting a new offer during PAYMENT drops an unconfirmed cash ride | Deferred (design: block Accept until cash confirmed) |
| 6 | IMPORTANT | "I don't feel safe" checkbox on early end is sent but the backend ignores it | Deferred, **needs product decision**: implement a safety flag in the backend or remove the checkbox. It must not stay as is for public launch; belongs with SOS (plan step 7) |
| 7 | IMPORTANT | Rider cancel unnoticed while app stays open | Deferred (poll every 15-30 s or backend push) |
| 8 | IMPORTANT (likely) | Rider photo probably always "unavailable": app caps download at 2 MB, backend returns an un-downscaled PNG of a 1280-2559 px photo | Deferred, verify with a real selfie; backend should return a downscaled JPEG |
| 9 | IMPORTANT | After app kill in the 60-min cash dispute window the cash step looks unconfirmed | Deferred (backend should expose `cashConfirmedAt`) |
| 10 | MINOR | Stale "New ride request" notification can stay forever | Fix now |
| 11 | MINOR | `OfferInbox` mutated from two dispatchers, no synchronisation | Deferred |
| 12 | MINOR | Offer arriving while state is Resolved gets a notification but no screen | Fix now |
| 13 | MINOR | Debug builds log the ride PIN and signed selfie URL | Fix now |
| 14 | MINOR | Failed Call 112 does nothing visible | Fix now |
| 15 | MINOR (latent) | No exit from PAYMENT unless method is CASH | Deferred (ONLINE blocked at booking until payments ship) |
| 16 | MINOR | Ride errors not announced to screen readers (no live region) | Deferred to the accessibility pass (plan step 9) |

## Checked and found sound
Double taps guarded; stale responses cannot move the screen backwards; late responses after Done/logout ignored; PIN not in saved state and arrived screen is screenshot-protected; on-screen fares use `Money.format`; STOMP socket closed properly.

## Backend items surfaced (for steps 2 and 5)
- Add a way to read driver availability, or exclude drivers with stale location from `findNearbyOnline`.
- Make accept idempotent for the same driver (return the booking).
- Expose `cashConfirmedAt` on the driver booking.
- Downscale the rider selfie before delivery.
- Handle or drop `safetyConcern` on driver early-end.

## Status after step 1
Fixed: F1, F2 (client), F3, review items 1, 2, 3, 4, 10, 12, 13 (PIN body; note the signed selfie download URL is still logged at URL level in debug builds), 14. Deferred: 5-9, 11, 15, 16 and the backend items above. Verified on the emulator: F1, F3, item 1, the full ride loop. Unit-tested only: F2 handling, items 2, 3, 4, 10, 12, 13, 14.

## Follow-ups noted during step 2
- Admin web (`frontend/admin`, step 8): `RideOversight.tsx` keeps a fixed `FLAG_TYPES` filter list and `lib/api-types.ts` is generated; add `LOW_RATING` and `REPEATED_LOW_RATING` to the filter and regenerate the types.
- `docs/api/ride-lifecycle.md` needs a short section pointing at the earnings snapshot and ratings rules.
