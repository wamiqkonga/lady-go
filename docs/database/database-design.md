# Lady Go — Database Design

Status: Draft v0.1 · Phase 0 (entities only; migrations arrive in Phase 2)

## 1. Conventions

- PostgreSQL 16 + PostGIS 3. Flyway migrations `V<n>__<name>.sql`; no manual production changes.
- Primary keys: `id uuid` (public-safe). No sequential IDs exposed.
- Timestamps `timestamptz` in UTC: `created_at`, `updated_at` on all mutable tables.
- Money: `bigint` minor units + `currency char(3)`.
- Geography: `geography(Point,4326)` with GiST indexes.
- Enumerations as `varchar` with CHECK constraints (easier to migrate than PG enums).
- Soft-delete via `status`/`deleted_at` only where required; audit and events tables are append-only.
- `city_id` on city-scoped data for multi-city growth.
- Optimistic locking: `version bigint` on bookings, rides, drivers, payments.

## 2. Entity overview

```text
users 1—1 customers
users 1—1 drivers 1—* vehicles
drivers 1—* driver_documents
customers 1—* saved_locations, emergency_contacts, payment_methods
customers 1—* bookings *—1 drivers (assigned)
bookings 1—* booking_events, ride_offers
bookings 1—1 rides 1—* location_history
bookings 1—* payment_transactions 1—* refunds
rides 1—* ratings
rides 1—* trip_shares
safety_incidents *—1 rides
cities 1—* pricing_rules
configuration, audit_logs, notification_logs, device_tokens, refresh_tokens, otp_requests
```

## 3. Tables

### users
`id, phone (unique), phone_verified_at, email (nullable, unique), name, password_hash (admins only), role, status, profile_photo_key, mfa_secret_enc (admins), last_login_at, created_at, updated_at`
Role: CUSTOMER, DRIVER, ADMIN, SUPER_ADMIN, SUPPORT. Status: ACTIVE, SUSPENDED, DELETED.

### customers
`id, user_id (fk), default_city_id, preferences jsonb, created_at, updated_at`

### saved_locations / emergency_contacts
- saved_locations: `id, customer_id, label, address, location geography, created_at`
- emergency_contacts: `id, customer_id, name, phone, relationship, created_at`

### drivers
`id, user_id, city_id, verification_status (NOT_SUBMITTED, PENDING_REVIEW, APPROVED, REJECTED, SUSPENDED), availability_status (OFFLINE, ONLINE, BUSY, ON_RIDE, SUSPENDED), current_location geography (last known, updated at sampled intervals only), current_location_at, rating_avg, rating_count, total_rides, license_number_enc, license_expiry, identity_verification_status, background_verification_status, verified_by, verified_at, rejection_reason, version, created_at, updated_at`

### driver_documents
`id, driver_id, vehicle_id (nullable), type (GOVERNMENT_ID, LICENSE, VEHICLE_REGISTRATION, INSURANCE, PHOTO...), storage_key, status (PENDING, APPROVED, REJECTED), reviewer_id, review_note, expires_at, created_at`

### vehicles
`id, driver_id, registration_number (unique), vehicle_type, make, model, color, capacity, year, verification_status, created_at, updated_at`

### bookings
`id, customer_id, driver_id (nullable), city_id, vehicle_type, pickup_location geography, pickup_address, destination_location geography, destination_address, estimated_distance_m, estimated_duration_s, estimated_fare, final_fare, currency, pricing_rule_id, payment_method, status, cancel_reason, cancelled_by, scheduled_at (nullable), ride_pin_hash, version, created_at, updated_at`

### booking_events (append-only)
`id, booking_id, from_status, to_status, actor_type, actor_id, reason, metadata jsonb, created_at`

### ride_offers
`id, booking_id, driver_id, status (OFFERED, ACCEPTED, REJECTED, EXPIRED, CANCELLED), offered_at, responded_at, distance_m`

### rides
`id, booking_id (unique), driver_id, customer_id, started_at, completed_at, start_location, end_location, actual_distance_m, actual_duration_s, status (ASSIGNED, STARTED, COMPLETED, CANCELLED), version, created_at, updated_at`

### location_history (partitioned by month)
`id, ride_id, driver_id, location geography, accuracy_m, heading, speed_mps, recorded_at`
Written only during active rides at a sampled interval; purged by a retention job.

### payment_transactions
`id, booking_id, customer_id, provider, provider_reference, method (CASH, ONLINE), amount, currency, status (INITIATED, PENDING, SUCCEEDED, FAILED, REFUNDED, PARTIALLY_REFUNDED), idempotency_key (unique), failure_code, created_at, updated_at, version`

### payment_webhook_events
`id, provider, event_id (unique per provider), payload (sanitised), processed_at, status`

### refunds
`id, transaction_id, amount, reason, status, provider_reference, created_by, created_at`

### ratings
`id, ride_id, rater_id, ratee_id, rater_role, score (1–5), comment, created_at` (unique per ride+rater)

### pricing_rules
`id, city_id, vehicle_type, base_fare, per_km, per_minute, minimum_fare, cancellation_fee, service_fee_pct/flat, surge_config jsonb, effective_from, effective_to, created_by, created_at` (versioned; never updated in place)

### configuration
`key, value, scope (GLOBAL|CITY), city_id, updated_by, updated_at` — e.g. `driver.search.radius`, `driver.response.timeout`, `ride.max.search.time`, `ride.max.distance`, `cancellation.grace.seconds`.

### cities
`id, name, country, timezone, currency, service_area geography(Polygon), active`

### safety_incidents
`id, ride_id, reporter_id, type (SOS, REPORT_DRIVER, REPORT_CUSTOMER, OTHER), severity, status (OPEN, ACKNOWLEDGED, ESCALATED, RESOLVED), location, notes, acknowledged_by, acknowledged_at, resolved_at, created_at`
plus `safety_incident_events` (append-only timeline).

### trip_shares
`id, ride_id, token_hash, expires_at, revoked_at, created_at`

### notification_logs / device_tokens
- device_tokens: `id, user_id, platform, token, app (CUSTOMER|DRIVER), last_seen_at`
- notification_logs: `id, user_id, event_type, channel, status, provider_ref, error_code, created_at` (no message bodies containing OTPs)

### refresh_tokens / otp_requests
- refresh_tokens: `id, user_id, token_hash, device_id, expires_at, revoked_at, replaced_by, created_at`
- otp_requests: `id, phone, code_hash, purpose, attempts, expires_at, consumed_at, created_at`

### audit_logs (append-only)
`id, actor_id, actor_role, action, target_type, target_id, metadata jsonb, ip, created_at`

## 4. Indexing (initial)

- GiST on all `geography` columns used for search (drivers.current_location, bookings pickup/destination, location_history).
- `bookings(customer_id, created_at desc)`, `bookings(driver_id, status)`, `bookings(status, created_at)`.
- `drivers(city_id, availability_status)`, `drivers(verification_status)`.
- Unique: `users.phone`, `vehicles.registration_number`, `payment_transactions.idempotency_key`, `payment_webhook_events(provider,event_id)`.
- `audit_logs(actor_id, created_at)`, `audit_logs(target_type, target_id)`.
- `location_history(ride_id, recorded_at)`; partitioned by `recorded_at`.

## 5. Migration plan (Phase 2)

```text
V1__extensions_and_base.sql       (postgis, uuid, cities, configuration)
V2__users_auth.sql                (users, refresh_tokens, otp_requests)
V3__customers.sql
V4__drivers_vehicles_documents.sql
V5__pricing.sql
V6__bookings_events_offers.sql
V7__rides_location_history.sql
V8__payments.sql
V9__ratings.sql
V10__safety_support.sql
V11__notifications_audit.sql
```

## 6. Data retention (proposal, needs confirmation)

| Data | Retention |
|------|-----------|
| location_history | 90 days, longer for rides tied to open/closed safety incidents under legal hold |
| OTP records | 24 hours |
| refresh tokens | until expiry + 30 days |
| audit_logs | minimum 2 years |
| notification_logs | 90 days |
| KYC documents | duration of driver relationship + legally required period (to be confirmed) |
