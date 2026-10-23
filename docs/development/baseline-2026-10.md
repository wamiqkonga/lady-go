# Baseline run, 2026-10-23 (step 0 of the Android public-launch plan)

Purpose: record what really works today before any new feature code. Environment: macOS, JDK 21, Gradle 8.13 (cached locally; CI uses 8.14.3), Android emulator Pixel 8 Pro API 35, backend from `scripts/dev-up.sh` (dev profile, throwaway Postgres).

## Automated checks (run locally)

| Check | Result |
|---|---|
| Backend `mvn verify` (Testcontainers, runs under amd64 emulation on Apple silicon, slow) | PASS, exit 0 |
| Driver app `:app:testDebugUnitTest :app:assembleDebug` | PASS |
| Customer app `:app:testDebugUnitTest :app:assembleDebug` | PASS |
| ktlint, detekt, lint, OpenAPI client drift, release build | NOT RUN here (CI runs them) |

## What was exercised by hand on the emulator

Driver app, against the real local backend:

| Flow | Result |
|---|---|
| Launch, login screen | OK |
| Bad phone input (`%2B...` typed by mistake) | Rejected, but the message is the generic "The request is invalid." |
| Wrong OTP | OK, clear message "The code is invalid or has expired." |
| Correct OTP, driver not yet approved | Shows "Application under review" |
| Go online (after approval, GPS set to seeded city) | OK |
| Incoming ride offer over STOMP, heads-up notification, 15 s countdown, Accept | OK |
| Accept leads to "Head to pickup" (route card, fare, Navigate, Call 112) | OK |
| "I've arrived" leads to the rider-photo screen | Screen is a secure window (screenshots black, by design). No crash. Content NOT visually verified. |

Customer app: launches to the login screen with no crash. Login, booking and tracking NOT exercised in this baseline.

NOT exercised yet (step 1 covers these): rider photo check, PIN entry, in-trip, complete + cash confirm, early end, resume after app kill, background/offline behaviour, low-end device, narrow width, the whole customer booking flow.

## Findings

| # | Severity | Finding |
|---|---|---|
| F1 | High | Offer notification shows fare in the wrong unit: heads-up text says "INR 12764" while the screen correctly shows ₹127.64 (paise shown as rupees, 100x). Money display bug. |
| F2 | High (partly a test artifact, see addendum) | State desync when going online for the first time. The battery-optimisation dialog appears; if an offer is matched meanwhile the server marks the driver BUSY while the app still shows "Offline" with the error "Availability cannot be changed directly while BUSY." The driver never sees the offer. Needs reconciliation on resume (fetch current availability and any active offer). |
| F3 | Medium | "Application under review" screen is a dead end: no refresh, no log out, no support contact. It also never updates after approval; the app must be force-closed. |
| F4 | Medium | Generic "The request is invalid." for a malformed phone number; should explain the expected format. |
| F5 | Low | Battery-optimisation dialog sits before the Go online result with no clear order of operations. Review the sequence with F2. |
| F6 | Dev tooling | `scripts/dev/seed-ride-offer.sh` read `['data']['id']` from the ride-selfie response; the field is `rideSelfieId`. Fixed in the working tree. |
| F7 | Dev tooling | OTP limit is 5 per hour per phone (`otp.resend.max.per.hour`), so repeated dev logins hit RATE_LIMITED. The limit works as designed. Dev note: reuse sessions, do not reset the stack needlessly. |
| F8 | Info | `mvn verify` takes long on Apple silicon because the `postgis/postgis:16-3.4` image is amd64 and runs under emulation. |

## Not-bugs worth remembering
- Black screenshot on the rider-photo screen is `FLAG_SECURE`; use `uiautomator dump` or the app's own test hooks to inspect it.
- `adb input text` needs a literal `+`, not `%2B`.

## Next
Step 1: fresh-context review of driver slice 3, fix F1 to F3, and finish the full ride loop on the emulator.

## Step 1 addendum (2026-10-23): full ride loop on the emulator

Environment notes learned the hard way:
- The Pixel 8 Pro AVD's `/data` was 97% full (178 MB free): installs failed with "Failed to free 334666937 bytes" and the whole emulator hung/ANR'd. Use another AVD (Pixel 9 Pro XL has 1.4 GB free) or wipe the full one deliberately. Headless (`-no-window -gpu host -memory 2048`) is much lighter on a memory-starved Mac.
- `scripts/dev-up.sh` can fail with a race: its admin login runs 11 ms before the bootstrap admin is created. Just `dev-down` and `dev-up` again.
- Rider photo showed "Photo unavailable." only because the backend returns a signed file URL with host `localhost:8080`, unreachable from the emulator. Run `adb reverse tcp:8080 tcp:8080` (or set `LADYGO_LOCAL_STORAGE_BASE_URL`). Not an app bug. The 2 MB cap concern (review item 8) is still unverified with a real selfie.
- Secure screens (arrived/PIN) block screenshots, but `adb shell uiautomator dump` works and gives text and bounds.

Verified on the emulator with the fixed build (driver +919900022233, cash ride):

| Step | Result |
|---|---|
| Offer notification text | "Estimated fare ₹127.64" (F1 fixed) |
| Accept leads to Head to pickup, shows new "I can't complete this ride" exit | OK |
| Kill app mid-ride, relaunch | Ride resumes at Head to pickup and the location foreground service is running (review item 1 fixed) |
| I've arrived | Server DRIVER_ARRIVED |
| Wrong PIN | "Incorrect ride PIN." |
| Correct PIN | Server RIDE_STARTED, "Trip in progress" |
| Complete ride | Server PAYMENT_PENDING, final fare 12764 |
| Cash collected | "Cash confirmed" banner (status stays PAYMENT_PENDING during the dispute window by design) |
| Back to home | Home shows Offline, location stopped. **But the server has released the driver to ONLINE** (ghost online), see the follow-up fix |
| Forced logout (backend DB reset, old tokens) | Clean redirect to login |
| Onboarding gate | Shows Check again and Log out (F3 fixed) |

### Correction on F2 and the ghost-online fix
- `scripts/dev/seed-ride-offer.sh` itself sends `PUT availability ONLINE` for the driver before creating the booking. So the first F2 observation ("server BUSY while the app shows Offline") was at least partly caused by the script flipping the server to ONLINE behind the app's back. Do not read it as proof of an app-only bug.
- The real-world half is independent of the script and confirmed in code: the server releases a driver BUSY to ONLINE when a ride ends, and the app (after a process kill) restarts as Offline with location and STOMP stopped, leaving a matchable "ghost online" driver.
- Fix (driver app): best-effort `PUT availability OFFLINE` at Home creation when no ride is tracked, and when a ride ends while Home is Offline. Verified: 277 unit tests, ktlint, detekt, assembleDebug pass; on the emulator the app sends the call at launch and gets 200. **Not verified end to end**: a clean "booking is not matched to an app-offline driver" run was contaminated by the script's ONLINE override and then blocked by the 5-per-hour OTP limit. Repeat with a seed script that does not force ONLINE (for example add a `--no-online` flag) after the hour resets.
