# Lady Go — MapsService Abstraction (Phase 4)

Status: v0.2 · **Approved 2026-10-04** (decisions M1–M3 below). Implementation proceeds against this version.

Scope: the `MapsService` SPI (named in `docs/architecture/backend-architecture.md` §7 and ADR-006/ADR-011, not yet implemented), its dev/test mock, and the first real implementation (`GoogleMapsService`), with the cost controls ADR-011 already committed to. No schema changes.

**M1 — decided**: a standalone top-level `com.ladygo.maps` package/module, a sibling of `auth`/`security`/`bookings` rather than nested under `locations` — provider SPIs stay out of the domain-module tree, matching how `auth`/`security` sit apart from `users`.

## 1. Interface

Matches the method list already named in the architecture doc, with concrete types:

```java
public interface MapsService {
    GeocodeResult geocode(String address);
    GeocodeResult reverseGeocode(double latitude, double longitude);
    RouteResult calculateRoute(Coordinate origin, Coordinate destination);
    double calculateDistanceMeters(Coordinate a, Coordinate b);   // straight-line, no API call - see section 4
    List<PlaceSuggestion> searchPlaces(String query, String sessionToken, Coordinate biasedNear);
    PlaceDetails getPlaceDetails(String placeId, String sessionToken);
}

record Coordinate(double latitude, double longitude) {}
record GeocodeResult(Coordinate coordinate, String formattedAddress) {}
record RouteResult(int distanceMeters, int durationSeconds, String polyline) {}
record PlaceSuggestion(String placeId, String description) {}
record PlaceDetails(Coordinate coordinate, String formattedAddress) {}
```

`calculateETA` (named in the architecture doc) is **not** a separate method here — it's `calculateRoute(...).durationSeconds()` from the current position to the destination, recomputed on demand. A separate method would just be this one with extra indirection; collapsing it keeps one code path for "how long will this take," used identically for the fare estimate and (from Phase 7) live ETA.

## 2. `MockMapsService` (dev/test)

`@Profile({"dev", "test"})`, same pattern as `MockSmsService` (`docs/security/auth-design.md` §9). Deterministic, no network call:

- `geocode`/`reverseGeocode`: returns a canned `GeocodeResult` built from the input itself (echoes the coordinate back with a synthetic formatted address like `"Mock address near 34.080,74.800"`) so tests can assert on predictable values without a fixture table.
- `calculateRoute`: straight-line (haversine) distance between origin/destination, duration derived from a configurable assumed average speed (`maps.mock.average.speed.kmh`, default 25 — realistic for city traffic) — good enough for deterministic fare-calculation tests without needing real road data.
- `searchPlaces`/`getPlaceDetails`: a small fixed in-memory list of fake places (3–5 entries) so autocomplete flows are testable end to end.

## 3. `GoogleMapsService` (real provider)

Selected the same way `SmsService` picks its real implementation in Phase 3: a profile-restricted bean (`@Profile({"staging", "prod"})`), backed by `LADYGO_GOOGLE_MAPS_API_KEY` (env only, never committed — same treatment as every other Phase 3 secret). This is the **server-side** key: Places/Geocoding/Routes calls only ever originate from the backend, never the mobile app, per ADR-011. It is a *different* key from the one baked into the Android/iOS app builds for rendering the map itself (that one is restricted by app ID/referrer to map-display only and carries no Places/Geocoding/Routes quota — already noted in the architecture doc, repeated here because it's easy to conflate the two keys and accidentally grant the mobile key more scope than it needs).

Startup guard mirroring `SmsStartupGuard`: if `staging`/`prod` is active and no real `MapsService` bean exists (or, defensively, if a mock bean is somehow present), fail fast rather than silently serving fake geocodes in a real environment.

## 4. ADR-011 cost controls, made concrete

The architecture doc lists these as bullet points; this is what each one actually becomes in Phase 4:

| Control | Phase 4 implementation |
|---|---|
| Autocomplete session tokens | `searchPlaces`/`getPlaceDetails` take a client-supplied `sessionToken` (a UUID the mobile app generates once per autocomplete session and reuses across keystrokes) and pass it straight through to Google's `sessiontoken` parameter, so a whole typing session bills once. Debouncing the keystrokes themselves is a mobile-client responsibility — the backend can't enforce timing, only pass the token through correctly. |
| Pin as source of truth; geocode only when it settles | `reverseGeocode` is a plain request/response endpoint; the "only call it when the pin stops moving" discipline is enforced by the mobile client calling it once per settle event, not per drag-frame. Documented here as a client contract, not backend logic. |
| Cache geocode results | **New, in-process, bounded cache** (Caffeine — a tiny, well-known library, not a new piece of infrastructure to run) keyed by rounded coordinate (5 decimal places ≈ 1.1m, enough to dedupe near-identical requests without conflating genuinely different addresses), TTL 24h. Explicitly **not** Redis: Redis isn't wired into the app yet (same reasoning as Phase 3's rate limiter — it becomes load-bearing in Phase 6/7, not before) and this cache's job is just to absorb duplicate requests within one instance's uptime; losing it on restart is fine. |
| Cache route estimates for a short window | Same Caffeine mechanism, separate cache, keyed by rounded origin+destination+vehicle type. **M2 — decided: TTL 90s.** |
| Straight-line distance for driver ranking | **Out of scope for Phase 4** — that's Phase 6 (Matching), and needs the driver GEO index infrastructure this phase doesn't touch. `calculateDistanceMeters` exists in the interface now (cheap, no API call, pure math) specifically so Phase 6 has it ready without changing this interface later. |
| Routes called only for fare estimate + assigned driver's ETA | Phase 4 delivers the fare-estimate half of this (`docs/api/booking.md` §1); "assigned driver's ETA" is Phase 6/7 (there's no driver assignment yet). |
| Limit live ETA refresh frequency | Phase 7 (real-time tracking) — nothing to build here yet. |
| Per-SKU call counters + budget alerts | Phase 4 adds a Micrometer counter `maps.calls` tagged `sku` (`geocode`, `reverse_geocode`, `route`, `places_autocomplete`, `place_details`) on every real-provider call (not on cache hits, not on the mock) — cheap to add now, and `docs/architecture/backend-architecture.md` §10 already commits to Micrometer metrics from the start. Actual Google Cloud budget alerts/quota caps are an ops/console configuration task (Phase 12), not application code. |

## 5. Fallback behaviour — M3, decided (asymmetric by endpoint)

`docs/product/requirements.md` §6 (non-functional) requires "graceful degradation when maps/SMS/payment providers fail." A `MapsService.calculateRoute` call that errors (timeout, 5xx from Google, quota exhausted) throws `MapsServiceException` (mapped to a new `ErrorCode.MAPS_UNAVAILABLE`, `503`). What happens next differs by caller, decided deliberately asymmetric:

- **`POST /bookings/estimate`** (`docs/api/booking.md` §1): catches `MapsServiceException` and **degrades** — falls back to `calculateDistanceMeters` (straight-line, pure math, can't fail the same way) plus an assumed average speed for duration, and marks the response `"approximate": true` so the client can show "estimated, may be less accurate" rather than a hard failure. Good enough for "should I open the app and look at a number," not good enough to commit money to.
- **`POST /bookings`** (`docs/api/booking.md` §2): **fails closed** — no fallback, no approximate booking. A `MapsServiceException` here propagates straight to `503`. Creating a real booking (and quoting a real fare) on an approximated distance is a worse failure mode than telling the customer to retry in a moment.

The same exception type drives both behaviours; which one happens is entirely in how each call site catches (or doesn't catch) it — not a flag or config, so there's no way to accidentally leave booking creation in degraded mode.

## 6. Decisions (2026-10-04)

| # | Decision |
|---|----------|
| M1 | Standalone `com.ladygo.maps` package. |
| M2 | Route-cache TTL: 90 seconds. |
| M3 | `/bookings/estimate` degrades to straight-line + `approximate: true` on a Maps outage; `/bookings` (create) fails closed with `503`, never approximates a real booking. |

Implementation follows; see `docs/development/roadmap.md` Phase 4 for exit criteria.
