# Lady Go — Admin Flows

Status: Draft v0.1 · Phase 0

## 1. Roles and permissions

| Capability | SUPPORT | ADMIN | SUPER_ADMIN |
|-----------|:------:|:-----:|:-----------:|
| View dashboard, rides, customers, drivers | yes | yes | yes |
| Handle support issues / safety incidents | yes | yes | yes |
| Approve/reject drivers and vehicles | no | yes | yes |
| Suspend/activate customers and drivers | no | yes | yes |
| Cancel / intervene in rides, adjust fares | no | yes | yes |
| View live map | yes | yes | yes |
| View historical location trails | no | no (needs explicit `LOCATION_HISTORY_READ` grant + reason) | yes (with reason, audited) |
| Edit pricing and business configuration | no | no | yes |
| Manage admin users and roles | no | no | yes |
| View audit log | no | read-only | yes |

Admin authentication: email + password + MFA (TOTP), short sessions, IP/device logging. Authorisation is enforced by the backend; the UI only hides what the API forbids.

## 2. Dashboard
Tiles: active drivers, online drivers, active rides, pending bookings, completed rides (today), cancelled rides (today), revenue (today), customers (total/new), driver verification requests, open support/safety issues, system alerts. Tiles link to filtered lists. Counts are refreshed via WebSocket or short polling fallback.

## 3. Live map (critical)
- Layers: online/available drivers, drivers on rides, active rides (pickup, destination, route line, status).
- Click a driver: name, driver ID, status, current ride, current location, vehicle, rating, today's rides, today's earnings.
- Click a ride: ride ID, customer, driver, pickup, destination, status, distance, duration, fare, timeline.
- Filters by status and city. Marker clustering for scale.
- Map access shows live positions only; viewing a historical trail is a separate, permissioned and audited action.
- Open safety incidents appear as highlighted markers.

## 4. Driver verification
1. Queue lists PENDING_REVIEW drivers ordered by submission time.
2. Reviewer opens documents through short-lived signed URLs (access logged).
3. Approve, reject with reason, or request re-upload of specific documents.
4. Decision triggers driver notification and audit entry (`DRIVER_APPROVED` / `DRIVER_REJECTED`).
5. Vehicles are verified separately.

## 5. Customer management
Search by phone/name/ID; view profile, ride history, payment history; suspend or activate with reason (`CUSTOMER_SUSPENDED`). Suspended customers cannot book; active bookings are handled manually.

## 6. Driver management
List/search; documents; ride history; earnings; current location (live only); suspend/activate with reason. Suspension forces the driver offline and blocks new offers.

## 7. Ride management
List, search, filter (status, date, city, driver, customer); detail view with event timeline; live rides; cancelled rides with reasons. Interventions: cancel booking (`BOOKING_CANCELLED_BY_ADMIN`), reassign driver, adjust fare (`FARE_MODIFIED`, reason mandatory), force-complete stuck ride.

## 8. Safety incident handling
1. SOS or report arrives in the safety queue with severity, live location, ride, driver and customer details.
2. Operator acknowledges (timestamped), contacts rider/driver, escalates to emergency services per the operating procedure, records notes.
3. Incident closed with outcome; all steps are retained in the incident timeline.
Operational procedure (24x7 coverage, escalation contacts) is a business decision required before launch.

## 9. Pricing and business configuration
SUPER_ADMIN edits base fare, per km, per minute, minimum fare, cancellation fee, service fee, surge rules (post-MVP), search radius, response timeout, max search time, max ride distance, supported cities. Each change is versioned, effective-dated, validated and audited (`CONFIGURATION_CHANGED`).

## 10. Payments
List transactions, filter by status, view refunds, initiate full/partial refund (ADMIN+), see webhook failures.

## 11. Audit log
Searchable by admin, action, target, time. Immutable append-only table. Recorded actions: `ADMIN_LOGIN`, `DRIVER_APPROVED`, `DRIVER_REJECTED`, `DRIVER_SUSPENDED`, `CUSTOMER_SUSPENDED`, `BOOKING_CANCELLED_BY_ADMIN`, `FARE_MODIFIED`, `CONFIGURATION_CHANGED`, plus document views and location-history access.

## 12. Reports (post-MVP beyond basics)
Revenue by day/city, ride volume, cancellation reasons, driver utilisation, acceptance rates, payment failures.
