# Lady Go — MapsService Abstraction (Phase 4)

Status: Draft v0.1 · for approval before implementation.

Scope: the `MapsService` SPI (named in `docs/architecture/backend-architecture.md` §7 and ADR-006/ADR-011, not yet implemented), its dev/test mock, and the first real implementation (`GoogleMapsService`), with the cost controls ADR-011 already committed to. No schema changes; this is a `config`/provider-adapter module, likely living at `com.ladygo.locations.maps` since it's geography-adjacent infrastructure the `locations` module already owns (`City`), or a new `com.ladygo.maps` package if you'd rather keep provider SPIs out of the domain-module tree the way `auth`/`security` are separate from `users` — your call, noted as OQ-M1.

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
| Cache route estimates for a short window | Same Caffeine mechanism, separate cache, keyed by rounded origin+destination+vehicle type, TTL 60–120s (traffic conditions move faster than a geocode, so a long TTL would make estimates stale) — OQ-M2 on the exact number. |
| Straight-line distance for driver ranking | **Out of scope for Phase 4** — that's Phase 6 (Matching), and needs the driver GEO index infrastructure this phase doesn't touch. `calculateDistanceMeters` exists in the interface now (cheap, no API call, pure math) specifically so Phase 6 has it ready without changing this interface later. |
| Routes called only for fare estimate + assigned driver's ETA | Phase 4 delivers the fare-estimate half of this (`docs/api/booking.md` §1); "assigned driver's ETA" is Phase 6/7 (there's no driver assignment yet). |
| Limit live ETA refresh frequency | Phase 7 (real-time tracking) — nothing to build here yet. |
| Per-SKU call counters + budget alerts | Phase 4 adds a Micrometer counter `maps.calls` tagged `sku` (`geocode`, `reverse_geocode`, `route`, `places_autocomplete`, `place_details`) on every real-provider call (not on cache hits, not on the mock) — cheap to add now, and `docs/architecture/backend-architecture.md` §10 already commits to Micrometer metrics from the start. Actual Google Cloud budget alerts/quota caps are an ops/console configuration task (Phase 12), not application code. |

## 5. Fallback behaviour

`docs/product/requirements.md` §6 (non-functional) requires "graceful degradation when maps/SMS/payment providers fail." For Phase 4 that means: a `MapsService` call that errors (timeout, 5xx from Google, quota exhausted) surfaces as a distinct, safe exception (`MapsServiceException`, mapped to `503` — the maps provider is unavailable, not the customer's fault, and not a `VALIDATION_FAILED`) rather than leaking a provider-specific error. It does **not** mean falling back to straight-line distance for a customer-facing fare estimate — an estimate silently computed a different way than advertised is worse than a clear "try again" error (OQ-M3 if you'd rather have a degraded-but-available estimate instead).

## 6. Open questions

| # | Question | Default if unanswered |
|---|----------|------------------------|
| OQ-M1 | Package location: `com.ladygo.locations.maps` (geography-adjacent) or a standalone `com.ladygo.maps`? | `com.ladygo.locations.maps` |
| OQ-M2 | Route-cache TTL — is 60–120s right, or does real-world traffic volatility call for shorter? | 90s |
| OQ-M3 | On a `MapsService` outage, return `503` (no estimate) or degrade to straight-line-distance-based estimate with a flag telling the client it's approximate? | `503`, no silent degradation for customer-facing fare numbers |

No code has been written for this phase yet.
