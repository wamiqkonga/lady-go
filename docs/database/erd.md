# Lady Go — Database ERD

Status: Phase 2 · generated from the migrated schema (Flyway V1–V11) on PostgreSQL 16 + PostGIS 3.

The diagrams below are generated from a live database after applying every migration, so they match the schema exactly. Narrative design, conventions and retention are in [database-design.md](database-design.md). The migrations in `backend/src/main/resources/db/migration` are the source of truth.

Legend: `PK` primary key, `FK` foreign key. `geography` columns are PostGIS `geography(…, 4326)`. Money is `int8` in minor units. Enumerations are `varchar` with CHECK constraints (mirrored by Java enums and verified by `EnumSchemaParityTest`).

## 1. Relationships

```mermaid
erDiagram
    bookings ||--o{ booking_events : "booking_id"
    bookings ||--o{ payment_transactions : "booking_id"
    bookings ||--o{ ride_offers : "booking_id"
    bookings ||--o{ rides : "booking_id"
    cities ||--o{ bookings : "city_id"
    cities ||--o{ configuration : "city_id"
    cities ||--o{ customers : "default_city_id"
    cities ||--o{ drivers : "city_id"
    cities ||--o{ pricing_rules : "city_id"
    customers ||--o{ bookings : "customer_id"
    customers ||--o{ emergency_contacts : "customer_id"
    customers ||--o{ payment_methods : "customer_id"
    customers ||--o{ payment_transactions : "customer_id"
    customers ||--o{ rides : "customer_id"
    customers ||--o{ saved_locations : "customer_id"
    drivers ||--o{ bookings : "driver_id"
    drivers ||--o{ driver_documents : "driver_id"
    drivers ||--o{ location_history : "driver_id"
    drivers ||--o{ location_history_2026_09 : "driver_id"
    drivers ||--o{ location_history_2026_10 : "driver_id"
    drivers ||--o{ location_history_2026_11 : "driver_id"
    drivers ||--o{ location_history_2026_12 : "driver_id"
    drivers ||--o{ location_history_2027_01 : "driver_id"
    drivers ||--o{ location_history_default : "driver_id"
    drivers ||--o{ ride_offers : "driver_id"
    drivers ||--o{ rides : "driver_id"
    drivers ||--o{ vehicles : "driver_id"
    payment_transactions ||--o{ refunds : "transaction_id"
    pricing_rules ||--o{ bookings : "pricing_rule_id"
    refresh_tokens ||--o{ refresh_tokens : "replaced_by"
    rides ||--o{ location_history : "ride_id"
    rides ||--o{ location_history_2026_09 : "ride_id"
    rides ||--o{ location_history_2026_10 : "ride_id"
    rides ||--o{ location_history_2026_11 : "ride_id"
    rides ||--o{ location_history_2026_12 : "ride_id"
    rides ||--o{ location_history_2027_01 : "ride_id"
    rides ||--o{ location_history_default : "ride_id"
    rides ||--o{ ratings : "ride_id"
    rides ||--o{ safety_incidents : "ride_id"
    rides ||--o{ trip_shares : "ride_id"
    safety_incidents ||--o{ safety_incident_events : "incident_id"
    users ||--o{ configuration : "updated_by"
    users ||--o{ customers : "user_id"
    users ||--o{ device_tokens : "user_id"
    users ||--o{ driver_documents : "reviewer_id"
    users ||--o{ drivers : "user_id"
    users ||--o{ notification_logs : "user_id"
    users ||--o{ pricing_rules : "created_by"
    users ||--o{ ratings : "ratee_id"
    users ||--o{ refresh_tokens : "user_id"
    users ||--o{ refunds : "created_by"
    users ||--o{ safety_incident_events : "actor_id"
    users ||--o{ safety_incidents : "acknowledged_by"
    users ||--o{ trip_shares : "created_by"
    vehicles ||--o{ driver_documents : "vehicle_id"
```

Notes: `bookings 1—1 rides` and `customers 1—1 users` are one-to-one via UNIQUE constraints (drawn above as one-to-many by the generator). `location_history` is a monthly range-partitioned table; its foreign keys point at `rides` and `drivers`. `audit_logs` deliberately has no foreign keys, so audit rows outlive what they describe.

## 2. Reference and configuration

```mermaid
erDiagram
    cities {
        uuid id PK
        varchar name
        char country
        varchar timezone
        char currency
        geography service_area
        bool active
        timestamptz created_at
        timestamptz updated_at
    }
    configuration {
        uuid id PK
        varchar key
        text value
        varchar value_type
        varchar scope
        uuid city_id FK
        text description
        uuid updated_by FK
        timestamptz updated_at
    }
    cities ||--o{ configuration : "city_id"
```

## 3. Identity and authentication

```mermaid
erDiagram
    users {
        uuid id PK
        varchar phone
        timestamptz phone_verified_at
        varchar email
        varchar name
        varchar password_hash
        varchar role
        varchar status
        varchar profile_photo_key
        text mfa_secret_enc
        bool mfa_enabled
        timestamptz last_login_at
        timestamptz created_at
        timestamptz updated_at
    }
    refresh_tokens {
        uuid id PK
        uuid user_id FK
        varchar token_hash
        varchar device_id
        timestamptz expires_at
        timestamptz revoked_at
        uuid replaced_by FK
        timestamptz created_at
    }
    otp_requests {
        uuid id PK
        varchar phone
        varchar code_hash
        varchar purpose
        int2 attempts
        timestamptz expires_at
        timestamptz consumed_at
        timestamptz created_at
    }
    refresh_tokens ||--o{ refresh_tokens : "replaced_by"
    users ||--o{ refresh_tokens : "user_id"
```

## 4. Customers

```mermaid
erDiagram
    customers {
        uuid id PK
        uuid user_id FK
        uuid default_city_id FK
        jsonb preferences
        timestamptz created_at
        timestamptz updated_at
    }
    saved_locations {
        uuid id PK
        uuid customer_id FK
        varchar label
        text address
        geography location
        timestamptz created_at
    }
    emergency_contacts {
        uuid id PK
        uuid customer_id FK
        varchar name
        varchar phone
        varchar relationship
        timestamptz created_at
    }
    customers ||--o{ emergency_contacts : "customer_id"
    customers ||--o{ saved_locations : "customer_id"
```

## 5. Drivers and vehicles

```mermaid
erDiagram
    drivers {
        uuid id PK
        uuid user_id FK
        uuid city_id FK
        varchar verification_status
        varchar availability_status
        geography current_location
        timestamptz current_location_at
        numeric rating_avg
        int4 rating_count
        int4 total_rides
        text license_number_enc
        date license_expiry
        varchar identity_verification_status
        varchar background_verification_status
        uuid verified_by FK
        timestamptz verified_at
        text rejection_reason
        int8 version
        timestamptz created_at
        timestamptz updated_at
    }
    vehicles {
        uuid id PK
        uuid driver_id FK
        varchar registration_number
        varchar vehicle_type
        varchar make
        varchar model
        varchar color
        int2 capacity
        int2 manufacture_year
        varchar verification_status
        timestamptz created_at
        timestamptz updated_at
    }
    driver_documents {
        uuid id PK
        uuid driver_id FK
        uuid vehicle_id FK
        varchar type
        varchar storage_key
        varchar status
        uuid reviewer_id FK
        text review_note
        timestamptz reviewed_at
        date expires_at
        timestamptz created_at
        timestamptz updated_at
    }
    drivers ||--o{ driver_documents : "driver_id"
    drivers ||--o{ vehicles : "driver_id"
    vehicles ||--o{ driver_documents : "vehicle_id"
```

## 6. Pricing

```mermaid
erDiagram
    pricing_rules {
        uuid id PK
        uuid city_id FK
        varchar vehicle_type
        char currency
        int8 base_fare
        int8 per_km
        int8 per_minute
        int8 minimum_fare
        int8 cancellation_fee
        numeric service_fee_pct
        int8 service_fee_flat
        jsonb surge_config
        timestamptz effective_from
        timestamptz effective_to
        uuid created_by FK
        timestamptz created_at
    }
```

## 7. Bookings and matching

```mermaid
erDiagram
    bookings {
        uuid id PK
        uuid customer_id FK
        uuid driver_id FK
        uuid city_id FK
        varchar vehicle_type
        geography pickup_location
        text pickup_address
        geography destination_location
        text destination_address
        int4 estimated_distance_m
        int4 estimated_duration_s
        int8 estimated_fare
        int8 final_fare
        char currency
        uuid pricing_rule_id FK
        varchar payment_method
        varchar status
        text cancel_reason
        varchar cancelled_by
        timestamptz scheduled_at
        varchar ride_pin_hash
        int8 version
        timestamptz created_at
        timestamptz updated_at
    }
    booking_events {
        uuid id PK
        uuid booking_id FK
        varchar from_status
        varchar to_status
        varchar actor_type
        uuid actor_id
        text reason
        jsonb metadata
        timestamptz created_at
    }
    ride_offers {
        uuid id PK
        uuid booking_id FK
        uuid driver_id FK
        varchar status
        int4 distance_m
        timestamptz offered_at
        timestamptz expires_at
        timestamptz responded_at
    }
    bookings ||--o{ booking_events : "booking_id"
    bookings ||--o{ ride_offers : "booking_id"
```

## 8. Rides and tracking

```mermaid
erDiagram
    rides {
        uuid id PK
        uuid booking_id FK
        uuid driver_id FK
        uuid customer_id FK
        varchar status
        timestamptz started_at
        timestamptz completed_at
        geography start_location
        geography end_location
        int4 actual_distance_m
        int4 actual_duration_s
        int8 version
        timestamptz created_at
        timestamptz updated_at
    }
    location_history {
        uuid id PK
        uuid ride_id FK
        uuid driver_id FK
        geography location
        float4 accuracy_m
        float4 heading
        float4 speed_mps
        timestamptz recorded_at PK
    }
    trip_shares {
        uuid id PK
        uuid ride_id FK
        uuid created_by FK
        varchar token_hash
        timestamptz expires_at
        timestamptz revoked_at
        timestamptz created_at
    }
    rides ||--o{ location_history : "ride_id"
    rides ||--o{ location_history : "ride_id"
    rides ||--o{ location_history : "ride_id"
    rides ||--o{ location_history : "ride_id"
    rides ||--o{ location_history : "ride_id"
    rides ||--o{ location_history : "ride_id"
    rides ||--o{ location_history : "ride_id"
    rides ||--o{ location_history : "ride_id"
    rides ||--o{ location_history : "ride_id"
    rides ||--o{ location_history : "ride_id"
    rides ||--o{ location_history : "ride_id"
    rides ||--o{ location_history : "ride_id"
    rides ||--o{ location_history : "ride_id"
    rides ||--o{ location_history : "ride_id"
    rides ||--o{ location_history : "ride_id"
    rides ||--o{ location_history : "ride_id"
    rides ||--o{ location_history : "ride_id"
    rides ||--o{ location_history : "ride_id"
    rides ||--o{ location_history : "ride_id"
    rides ||--o{ location_history : "ride_id"
    rides ||--o{ location_history : "ride_id"
    rides ||--o{ location_history : "ride_id"
    rides ||--o{ location_history : "ride_id"
    rides ||--o{ location_history : "ride_id"
    rides ||--o{ location_history : "ride_id"
    rides ||--o{ location_history : "ride_id"
    rides ||--o{ location_history : "ride_id"
    rides ||--o{ location_history : "ride_id"
    rides ||--o{ location_history : "ride_id"
    rides ||--o{ location_history : "ride_id"
    rides ||--o{ location_history : "ride_id"
    rides ||--o{ location_history : "ride_id"
    rides ||--o{ location_history : "ride_id"
    rides ||--o{ location_history : "ride_id"
    rides ||--o{ location_history : "ride_id"
    rides ||--o{ location_history : "ride_id"
    rides ||--o{ location_history : "ride_id"
    rides ||--o{ location_history : "ride_id"
    rides ||--o{ location_history : "ride_id"
    rides ||--o{ location_history : "ride_id"
    rides ||--o{ location_history : "ride_id"
    rides ||--o{ location_history : "ride_id"
    rides ||--o{ location_history : "ride_id"
    rides ||--o{ location_history : "ride_id"
    rides ||--o{ location_history : "ride_id"
    rides ||--o{ location_history : "ride_id"
    rides ||--o{ location_history : "ride_id"
    rides ||--o{ location_history : "ride_id"
    rides ||--o{ location_history : "ride_id"
    rides ||--o{ trip_shares : "ride_id"
```

## 9. Payments

```mermaid
erDiagram
    payment_methods {
        uuid id PK
        uuid customer_id FK
        varchar provider
        varchar provider_token
        varchar type
        varchar display_label
        bool is_default
        timestamptz created_at
    }
    payment_transactions {
        uuid id PK
        uuid booking_id FK
        uuid customer_id FK
        varchar provider
        varchar provider_reference
        varchar method
        int8 amount
        char currency
        varchar status
        varchar idempotency_key
        varchar failure_code
        int8 version
        timestamptz created_at
        timestamptz updated_at
    }
    payment_webhook_events {
        uuid id PK
        varchar provider
        varchar event_id
        jsonb payload
        varchar status
        timestamptz received_at
        timestamptz processed_at
    }
    refunds {
        uuid id PK
        uuid transaction_id FK
        int8 amount
        text reason
        varchar status
        varchar provider_reference
        uuid created_by FK
        timestamptz created_at
        timestamptz updated_at
    }
    payment_transactions ||--o{ refunds : "transaction_id"
```

## 10. Ratings and safety

```mermaid
erDiagram
    ratings {
        uuid id PK
        uuid ride_id FK
        uuid rater_id FK
        uuid ratee_id FK
        varchar rater_role
        int2 score
        varchar comment
        timestamptz created_at
    }
    safety_incidents {
        uuid id PK
        uuid ride_id FK
        uuid reporter_id FK
        varchar type
        varchar severity
        varchar status
        geography location
        text notes
        uuid acknowledged_by FK
        timestamptz acknowledged_at
        uuid resolved_by FK
        timestamptz resolved_at
        bool legal_hold
        timestamptz created_at
        timestamptz updated_at
    }
    safety_incident_events {
        uuid id PK
        uuid incident_id FK
        uuid actor_id FK
        varchar event_type
        text notes
        jsonb metadata
        timestamptz created_at
    }
    safety_incidents ||--o{ safety_incident_events : "incident_id"
```

## 11. Notifications and audit

```mermaid
erDiagram
    device_tokens {
        uuid id PK
        uuid user_id FK
        varchar platform
        varchar app
        varchar token
        timestamptz last_seen_at
        timestamptz created_at
    }
    notification_logs {
        uuid id PK
        uuid user_id FK
        varchar event_type
        varchar channel
        varchar status
        varchar provider_ref
        varchar error_code
        timestamptz created_at
    }
    audit_logs {
        uuid id PK
        uuid actor_id
        varchar actor_role
        varchar action
        varchar target_type
        varchar target_id
        jsonb metadata
        inet ip
        timestamptz created_at
    }
```

## Invariants enforced by the database

These hold even if application code has a bug or two workers race. Each is covered by a test in `DatabaseConstraintsTest`.

| Rule | Mechanism |
|------|-----------|
| Phone numbers are E.164 and unique | CHECK + UNIQUE on `users.phone` |
| Only admin roles may have a password hash or MFA secret | CHECK on `users` |
| E-mail is unique ignoring case | unique index on `lower(email)` |
| A driver can only be ONLINE / BUSY / ON_RIDE if verification is APPROVED | CHECK `ck_drivers_online_requires_approval` |
| A driver holds at most one committed booking | partial unique index `uq_bookings_one_active_per_driver` |
| A booking is accepted by exactly one driver | partial unique index `uq_ride_offers_one_accepted` |
| A CANCELLED booking records who cancelled it | CHECK `ck_bookings_cancelled` |
| At most one pricing rule applies per city and vehicle type at any moment | exclusion constraint on `tstzrange(effective_from, effective_to)` |
| Payment requests are idempotent; provider webhooks are processed once | UNIQUE `idempotency_key`; UNIQUE `(provider, event_id)` |
| One rating per rater per ride; scores 1–5; no self-rating | UNIQUE + CHECKs on `ratings` |
| Event and audit history cannot be edited | `BEFORE UPDATE` trigger raising an error on `booking_events`, `safety_incident_events`, `audit_logs` |
| Location trails exist only per ride, in monthly partitions | partitioned `location_history` + default partition; `create_location_history_partition(date)` |
| Global configuration keys are unique | unique index on `(key, scope, coalesce(city_id, …))` |

## Operational notes

- **Partitions:** migration V7 creates partitions for the previous month through the next three. A scheduled job (Phase 12) must call `create_location_history_partition(next_month)` ahead of time; until then rows fall into `location_history_default`, which is a safety net, not a place to leave data (a partition cannot be created over a range that already has rows in the default partition).
- **Retention:** 90-day location history is enforced by dropping old partitions; rides with `safety_incidents.legal_hold = true` must be exempted by the retention job (Phase 10/12).
- **No JPA entity for `location_history`:** it is high-volume and append-only, so Phase 7 writes it with JDBC batch inserts.
- **Cross-module references are plain UUIDs** in the Java entities (not `@ManyToOne`), keeping modules decoupled; the foreign keys still exist in the database.
- **Encrypted columns** (`license_number_enc`, `mfa_secret_enc`) hold application-level ciphertext; key management arrives with Phase 3/5.
- **Dev seed:** `db/dev/R__dev_seed_city.sql` adds a placeholder Srinagar city and is loaded only by the `dev` profile.
