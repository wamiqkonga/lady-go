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
Endpoint `GET /api/v1/admin/dashboard` (SUPPORT and above, read only, counts and timestamps only, no personal data). The page polls it every 30 seconds while the tab is visible. "Today" is the Asia/Kolkata calendar day; weeks start on Monday in the same zone.

Tiles, each linking to its screen: active drivers (APPROVED), online drivers (ONLINE with a position newer than `admin.livemap.max_age.minutes`), active rides, pending bookings, completed and cancelled rides today, cash fares collected today, customers (total and new in 7 days), driver verification requests, open safety incidents, system alerts.

- **Cash fares** is the sum of final fares of today's completed cash rides, in rupees. It is money riders paid captains, not Lady Go revenue, and is labelled that way (the pilot is cash only).
- **System alerts** counts admin sign-in addresses with at least `admin.login.alert.failures` failed attempts in the last `admin.login.alert.window.minutes` (the same rule as the `admin_login_failures_high` log line). Nothing else feeds it, and the tile says so.
- **Driver review wait** is measured from the same "submitted at" as the verification queue (first document, vehicle or declaration sent, else account creation). Over target when it exceeds `verification.queue.overdue.hours` (default 24, range 1-168; the same limit the queue uses).
- **SOS acknowledgement** is the median of `ACKNOWLEDGED` minus `CREATED` timeline events over SOS raised in the last 30 days, plus how long the longest-waiting unacknowledged SOS has waited. Over target when it exceeds `admin.dashboard.sos_ack_target.minutes` (default 5, range 1-60). Both tiles turn red and say "Over target".
- **60-day adoption block**: captains registered, captains who finished every step (vehicle, RC document, selfie and signed declaration, reviewed or not), captains approved, riders who completed a ride, and rides completed per week for the last 8 weeks (a bar list that is also its own text equivalent).

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
