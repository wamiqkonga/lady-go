# Lady Go — Real-Time Architecture

Status: Draft v0.1 · Phase 0

## 1. Goals
- Customer sees driver moving on the map during an assigned ride, with low latency.
- Admin sees all online drivers and active rides live.
- Ride offers reach drivers immediately.
- No unnecessary writes to PostgreSQL; no tracking when a driver is offline.

## 2. Data flow

```text
Driver App --(WS STOMP /app/driver/location, or HTTPS fallback)--> Spring Boot (LocationIngestService)
        1. authenticate + authorise (driver is ONLINE, owns the session)
        2. validate (lat/lon bounds, timestamp skew, speed sanity)
        3. Redis GEOADD drivers:{city} + HSET driver:{id}:live (TTL 30s)
        4. publish to Redis channel  rt:driver:{id}
        5. if driver on ride: publish rt:ride:{rideId}
        6. sampler: if on ride and >=N seconds since last stored point -> INSERT into location_history (PostGIS)
Redis pub/sub --> every backend node --> STOMP broker --> subscribed clients
```

## 3. Redis keys

| Key | Type | TTL | Purpose |
|-----|------|-----|---------|
| `geo:drivers:{cityId}` | GEO (sorted set) | none; members removed on offline/expiry sweep | Nearby search for matching |
| `driver:{id}:live` | Hash (lat, lon, heading, speed, accuracy, ts, status) | 30 s, refreshed on update | Live state, presence |
| `driver:{id}:offer` | String (offerId) | `driver.response.timeout` | Atomic claim so a driver gets one offer at a time |
| `ride:{id}:driver` | Hash | ride duration | Fast lookup for tracking |
| `rl:{scope}:{key}` | Counter/token bucket | window | Rate limits |
| `otp:{phone}` | Hash (hash, attempts) | 5 min | OTP verification state |
| `config:cache` | Hash | short | Business configuration cache |
| `rt:*` | Pub/Sub channels | n/a | Cross-node fan-out |

A presence sweeper removes drivers from the GEO set when their `live` key expires, treating them as offline.

## 4. WebSocket/STOMP design

Endpoint `/ws` (WSS only), authenticated during CONNECT using the JWT (header, not query string). Topic authorisation is enforced by a channel interceptor.

| Destination | Direction | Who may subscribe/send |
|-------------|-----------|------------------------|
| `/app/driver/location` | client->server | DRIVER, only while ONLINE/ON_RIDE |
| `/user/queue/offers` | server->driver | the driver |
| `/user/queue/booking` | server->customer | the booking owner (state changes, assignment) |
| `/topic/ride/{rideId}/driver-location` | server->clients | the ride's customer and driver; share-link viewers via a scoped token |
| `/topic/admin/drivers` | server->admin | ADMIN, SUPPORT, SUPER_ADMIN |
| `/topic/admin/rides` | server->admin | same |
| `/topic/admin/incidents` | server->admin | same |

Admin map subscriptions are viewport/city scoped and throttled (e.g. 1 aggregated frame per second) to avoid flooding.

## 5. Update cadence and battery
- Driver app: ONLINE idle ~ every 10 s; assigned/ON_RIDE ~ every 3–5 s; adaptive to speed/movement; no transmission when OFFLINE.
- Server downsamples what it forwards to customers (e.g. max 1 update / 2 s) and to admins.
- History sampling only during an active ride, e.g. every 10–15 s, plus significant events (arrived, started, completed, SOS).

## 6. Reliability
- Clients reconnect with exponential backoff, resubscribe, then fetch current state via REST (`GET /bookings/{id}`) so missed messages don't matter.
- Location messages carry timestamps; stale or out-of-order points are dropped.
- HTTP fallback for location when WebSocket is unavailable.
- Offers: delivered over WebSocket plus push notification fallback; expiry owned by the server.
- Backpressure: per-driver rate limit; drop-oldest policy for slow consumers.

## 7. Security and privacy
- Authorise every subscription (ownership checks), never rely on topic names being secret.
- Share-trip links use a signed, expiring, revocable token with a view-only scope that exposes only the minimum data.
- No customer location is broadcast to anyone other than the assigned driver and only during the active ride.
- Admin map shows live data only; history queries go through a separate audited API.
- Logs contain no coordinates beyond coarse debug when explicitly enabled in non-production.

## 8. Scaling notes
- Backend nodes stateless; each node holds its own WebSocket sessions; Redis pub/sub relays messages. When a single Redis becomes a bottleneck, shard channels by city or move to Redis Streams/Kafka.
- Location ingest can be extracted to a dedicated service later; contract stays the same.

## 9. Test plan
Unit tests for validation and sampler logic; integration tests with Testcontainers (Redis+Postgres) for the full ingest -> pub/sub -> subscriber path; authorisation tests for each topic; load test target of 2,000 simulated drivers before launch.
