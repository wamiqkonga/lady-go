# Driver Android app: slice 4, the driver's account area

Status: **APPROVED 2026-10-24 (Wamiq).** Decisions: Q1 no support numbers yet (Help shows "Support details coming soon" plus Call 112; build the config endpoint); Q2 rating the rider stays skippable; Q3 this week and last week only; Q4 English only. Step 3 of `docs/development/plans/2026-10-23-android-public-launch-plan.md`. Builds on `docs/product/driver-android-design.md` (slices 1-3) and `docs/api/driver-earnings-ratings.md` (backend, committed).

## 1. Why (60-day adoption link)
A driver who cannot see what she earned, what riders thought of her, or how to get help will stop driving. This slice gives her those, and closes the loop after each ride by letting her rate the rider (which keeps riders accountable and is part of the women-safety promise).

## 2. Screens and navigation
Home stays the landing screen (Go online, status). Add an **"Account" area** reached from a bottom bar with three items, shown only on Home and its children (never during an offer or active ride, which stay full-screen):

| Tab | Screen | Backed by |
|---|---|---|
| Home | existing Home | existing |
| Earnings | Earnings: week selector (this week / last week), total net, gross, commission, rides, cash vs online, per-day list | `GET /drivers/me/earnings` |
| Rides | Ride history: newest first, infinite scroll (page size 20), each row: date, pickup to destination, fare, rating chip; tap opens ride detail (read-only) | `GET /drivers/me/rides` |
| Me | Rating summary, documents and vehicle status, Help, Log out | `GET /drivers/me/ratings/summary`, `GET /drivers/me/documents`, `GET /drivers/me/vehicle`, `GET /drivers/me` |

Plus one flow outside the tabs: **Rate the rider** after a ride (see 3).

## 3. Behaviour details
- **Rate the rider:** when the driver presses Done on a finished ride, a bottom sheet asks for 1-5 stars and optional tags (RESPECTFUL, ON_TIME) and a skip button. Submits `POST /drivers/me/bookings/{id}/rating`. Skippable; the same sheet is reachable later from that ride in history while the 7-day window is open (409 "already rated" shows "You already rated this ride"). Never blocks going back to Home, never blocks going online.
- **Earnings:** all amounts through the existing `Money.format` (paise to rupees). If `commissionConfigured` is false the commission row reads "Commission not set yet" and net equals gross; no invented number. Week boundaries use the city time zone supplied by the API (the app passes dates, the server buckets).
- **History privacy:** addresses may be null (cancelled rides); show "Address hidden for this ride" rather than blank. No rider name or phone anywhere. `ratedByRider` is shown only as a small "Rider has rated" hint; the score is never shown.
- **Rating summary:** average and count always; the distribution and tag counts appear only when the API returns them (5 or more ratings); below that the card says "Distribution appears after 5 ratings".
- **Documents and vehicle:** read-only status per document (APPROVED, PENDING_REVIEW, REJECTED with the admin note) and the vehicle. Any change or re-upload goes to the website through the existing "Continue on the Lady Go website" button (no new upload flow in the app).
- **Help:** a card with "Call support" and "Message on WhatsApp" using the number from server config (see Q1) and the always-visible Call 112 button already used elsewhere. Includes the app version and a "Copy diagnostics" action (app version, device model, anonymised driver id; no tokens, no location).
- **States for every screen:** loading skeleton, empty ("No rides yet. Go online to get your first ride."), error with Retry and the server's real message, offline banner when there is no connection with the last loaded data kept on screen. Pull to refresh.
- **Accessibility and low-end phones:** all touch targets at least 48dp, content descriptions on icons, text scales to 200 percent without clipping, works on a 360dp-wide phone and Android 8 (minSdk 26), lists use lazy loading, no images fetched on these screens.
- **Security:** screens read only the signed-in driver's data; nothing from these screens is written to logs; no screenshots blocked here (nothing sensitive beyond the driver's own numbers). Debug-build network logging keeps the existing redaction and adds the rating body to the redacted set (comments are never sent in v1; only score and tags).

## 4. Architecture
Same pattern as slices 1-3: one ViewModel per screen, repositories wrapping the generated clients (`DriverEarningsControllerApi`, `RatingControllerApi`, `DriverControllerApi`), Hilt, `ApiResult`, no new libraries. The bottom bar is a thin composable owned by the nav host; the existing activity-scoped ride and offer ViewModels are untouched. Pagination via a simple paging helper (no Paging 3 dependency). Strings in `strings.xml` (ready for later translation).

## 5. Test plan
- Unit: each ViewModel (loading, success, empty, error, retry), earnings formatting incl. unset commission and zero rides, week selection dates, history pagination and de-duplication, rating submit (success, 409, window closed 4xx, offline), summary low-count shape, null addresses.
- Compose tests: each screen's states; bottom bar hidden during offer/active ride; rate sheet skip and submit.
- User-level run on the emulator against the local stack (my Rule 3): seed a driver with 3 completed rides on different days, open every tab, scroll history, rate a rider after a ride, try wrong inputs (airplane mode, backend down, token expiry mid-load), check 360dp width and 200 percent font scale, then uiautomator-verify the text.
- CI parity locally: `testDebugUnitTest ktlintCheck detekt assembleDebug checkOpenApiClientDrift`.

## 6. Build order (each its own commit)
1. Nav shell and bottom bar with empty tabs. 2. Earnings. 3. Ride history and detail. 4. Rate-the-rider sheet and wiring after Done. 5. Me tab (rating summary, documents and vehicle). 6. Help and diagnostics. 7. Fresh-context review, emulator pass, fixes.

## 7. Risks
Bottom bar interfering with the ride and offer flows (mitigated by showing it only on Home-tree routes and a nav test); stale data after a ride (refresh on resume and after Done); the 5-rating privacy threshold looking empty for new drivers (copy explains it).

## 8. Open questions (need your answer before build)
- **Q1 Support contact:** what phone number and WhatsApp number should drivers use for help, and who answers? Needs a tiny backend endpoint `GET /api/v1/support/contact` backed by admin config keys `support.phone` and `support.whatsapp` (so you can change it without an app release). Until you give numbers the Help card shows "Support details coming soon" and only Call 112.
- **Q2 Rating the rider:** OK to keep it skippable (recommended), or required before the driver can go back online?
- **Q3 Earnings range:** this week and last week selector only in v1 (recommended), or a free date range picker?
- **Q4 Language:** English only for launch, or add Hindi strings in this slice? (Adds translation work and review.)

## 9. Web Admin tickets (paste into Web Admin; staging first)
**T12. Set support contact config.** Why: the Help card needs it. Steps: after the backend endpoint ships, admin web (staging), Config, set `support.phone` and `support.whatsapp` with a reason. Acceptance: a staging driver app shows both and the call/WhatsApp buttons open the dialer/WhatsApp. Owner: you.
