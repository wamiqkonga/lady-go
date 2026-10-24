# Step 3 user-level run (driver account area), 2026-10-24

Emulator Pixel 9 Pro XL (API 35, headless), local dev stack (backend with migrations through V33), driver app build from commit 92a30a9. Ride 1 completed over the API (both ratings), ride 2 driven entirely in the app.

| Check | Result |
|---|---|
| Bottom bar on Home, hidden during an active ride | OK |
| Earnings tab: This week 19 to 25 Oct, 1 ride, 127.64 rupees, "Commission not set yet", cash vs online, per-day list | OK |
| Rides tab: completed ride, addresses, fare, "Rider has rated" hint and "You rated 4" chip | OK |
| Me tab: rating 5.0 from 1 rating, "Distribution appears after 5 ratings", 5 documents Approved, vehicle Maruti Dzire JK01AB1234 | OK |
| Help: "Support details coming soon" (from GET /support/contact with both keys unset), Call 112, Copy diagnostics ("Diagnostics copied", clipboard preview shows app and Android version only) | OK |
| Ride 2 in the app: accept, arrived (rider photo loaded after `adb reverse tcp:8080 tcp:8080`), wrong PIN rejected earlier, right PIN, start, complete, cash confirmed | OK |
| Done opens the rate-the-rider sheet over Home; 4 stars + Respectful + Submit | OK, row shows "You rated 4", Earnings shows 2 rides and 255.28 rupees |
| Activity recreated mid-ride (adb relaunch): Home restarts Offline, at ride end the app sends PUT availability (200) | OK, ghost-online fix exercised |
| Airplane mode while switching to Last week | "Could not load your earnings. Could not reach the server..." with Retry; recovers when back online |
| 360dp wide, 200 percent font | Me and Rides wrap gracefully. DEFECTS: the "Earnings" tab label breaks as "Earnin/gs"; Earnings label/value rows break mid-word ("commissio/n", "Commissi/on not set yet"). Fix sent to the builder |

Notes for future runs
- The admin login rejects a TOTP code reused inside the same 30 s window (replay protection): wait for the next window before a second admin login.
- Pressing Call 112 opens the dialer pre-filled with 112; it never dials by itself. A mistaken tap while scripting only opened the dialer.
- Driver OTP limit is 5 per hour per phone and a 30 s cooldown; plan logins accordingly.
- Not covered here: swipe-away of the rate sheet, a 409 on re-rating, process death during the sheet (unit tested only); rating from ride detail in history; support numbers set (unit tested only).
