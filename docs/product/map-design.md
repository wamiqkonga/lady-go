# Map experience: rider pin-drop and driver live map

Status: BUILT, 2026-10-31. Backend (reverse-geocode, route polyline, rider driver-location), rider pin-drop, fare-screen route map, driver app maps (offer area, ride, home) and the driver's car on the rider's track screen are all implemented. Checked on an emulator with a real Maps key, except the car moving on the track screen (that screen is screenshot-blocked, so only the map's presence was confirmed, plus unit and integration tests). Still open: release key restriction (decision 6), billing owner (decision 2).

## 1. Goal

Today the rider types an address and the driver app hands off to the Google Maps app. Riders and drivers expect a
real map, as in Uber and Rapido:

- **Rider:** choose pickup and drop by moving a map under a fixed pin, then watch the driver arrive on the map.
- **Driver:** see the pickup, the route and her own position on a map inside the app.

Success looks like: a rider books a ride without typing, and a driver can follow a ride without leaving the app.

## 2. What exists today (checked in the repo)

| Part | State |
|---|---|
| Provider decision | Google Maps Platform, ADR-011 (`docs/architecture/backend-architecture.md`) |
| Backend `MapsService` | Designed in `docs/api/maps-service.md`: geocode, reverse geocode, route, place search, with cost controls (session tokens, caching, call counters). Mock in dev, Google in staging and prod. |
| Rider app | Android `Geocoder` plus typed destination search. No map. |
| Driver app | Offer and ride screens have no map. "Navigate" opens the Google Maps app. |
| API gaps | No public endpoint for the apps to reverse-geocode a pin. No endpoint for the rider to see the driver's live position. |

## 3. Rider app: pin-drop booking

Flow:

1. Open "Book a ride". The map centres on her GPS position. A pin sits fixed at the screen centre.
2. She drags the map. While moving, the address area shows a short "Finding address..." state.
3. When the map settles (about 400 ms without movement) the app asks the backend for the address of that point. This is
   the "pin is the source of truth, look up the address only when it settles" rule already in ADR-011.
4. "Confirm pickup", then the same screen for the drop. A search box and saved places stay available as shortcuts.
5. Fare estimate screen shows the route line between pickup and drop.
6. After booking, the map shows the driver's car approaching, with ETA and the ride PIN card on top.

Rules:

- The pickup defaults to her real GPS position, and she can drag it. Drop has no default.
- The service area (city polygon) is drawn faintly. A pin outside it shows "We do not serve this area yet" and blocks booking.
- Works with the screen reader: the address text is the accessible name of the pin, and Confirm is a normal button.
- No map keys, coordinates or addresses are logged.

## 4. Driver app: live map

- **Offer card:** small map preview with an approximate pickup area (the exact point stays hidden until she accepts, as in
  the current design) and the distance.
- **To pickup / in trip:** map with her position, the pickup or drop pin and the route line. "Navigate" still hands off to the Google Maps app
  for turn-by-turn.
- **Home:** her position on a map while online, behind the existing status card.
- Keeps the existing 5-second-class location updates. No new tracking while offline.

## 5. Backend and API work this needs

1. `POST /api/v1/maps/reverse-geocode` (customer and driver roles): takes a coordinate, returns the address. Wraps the existing
   `MapsService`, with its cache and rate limits.
2. `GET /api/v1/bookings/{id}/driver-location` for the rider (only for her own booking, only while the ride is active), or a
   per-ride WebSocket topic. This is part of the unfinished real-time work (Phase 7).
3. Route polyline in the fare estimate response (the `MapsService` already returns one) so the app can draw it.
4. Regenerate `docs/api/openapi.json` and both Kotlin API clients, which CI drift-checks.

## 6. Build order

| Step | Work | Notes |
|---|---|---|
| 1 | Google Cloud project and a restricted debug key | Needs you. See decisions. |
| 2 | Backend reverse-geocode endpoint | Small. Tests with the mock. |
| 3 | Rider pin-drop screens | Maps Compose, one map screen reused for pickup and drop. |
| 4 | Route line on fare estimate | Uses the polyline from step 5.3. |
| 5 | Driver map on offer, ride and Home | Read-only, no new API. |
| 6 | Driver position on the rider's map | Depends on the live-location endpoint. Last, because it is the largest piece. |

Rough effort: rider 1 to 2 weeks, driver about 1 week, backend about 2 to 3 days, plus the live-location work.

## 7. Decisions

| # | Decision | Answer |
|---|---|---|
| 1 | Map provider | **Google Maps** (matches ADR-011 and the backend) |
| 2 | Who owns the Google Cloud account and billing | **Still open: needs you** |
| 3 | Rider typed search | **Keep as a shortcut** next to the pin |
| 4 | Driver position on the rider's map in the first release | **Reversed 2026-10-31:** built now, by polling `GET /bookings/{id}/driver-location` every 5s (fresh within 60s, own booking only, active statuses only) rather than WebSocket |
| 5 | Pickup precision | **Free drop**, no snapping to roads |
| 6 | Release keys | **Still open:** when the upload key and Play App Signing exist, restrict the Maps key to those SHA-1 fingerprints |

With decision 4, build step 6 (driver position on the rider's map) and API item 5.2 move to a second release.

## 8. Risks

| Risk | Mitigation |
|---|---|
| Map billing surprises | Use the existing cost controls: settled-pin lookups only, geocode cache, per-SKU counters and a Google budget alert. |
| Key leaks from the APK | Restrict the key to the package names and SHA-1 fingerprints. Keep it out of git (Gradle property or CI secret). |
| Weak GPS indoors gives a wrong pin | Always let her drag the pin, and show the resolved address before she confirms. |
| Maps do not render on low-end phones | Test on a low-RAM emulator profile before release. |
